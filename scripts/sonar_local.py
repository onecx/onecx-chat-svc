#!/usr/bin/env python3
"""Run the locally installed Sonar Java language server without uploading code."""

import argparse
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET


def discover_extension(home):
    candidates = []
    for location in (".vscode", ".vscode-server", ".vscode-insiders", ".vscode-server-insiders"):
        for path in (home / location / "extensions").glob("sonarsource.sonarlint-vscode-*"):
            version = re.search(r"vscode-(\d+(?:\.\d+)*)", path.name)
            if version and complete_extension(path):
                candidates.append((tuple(map(int, version[1].split("."))), path))
    if not candidates:
        raise RuntimeError("Install SonarQube for IDE with its Java analyzer, or specify --extension.")
    return max(candidates)[1]


def complete_extension(path):
    return (path / "server/sonarlint-ls.jar").is_file() and (path / "analyzers/sonarjava.jar").is_file()


def maven_config(root):
    reports = sorted((root / "target/surefire-reports").glob("TEST-*.xml"))
    for report in reports:
        properties = {p.attrib["name"]: p.attrib["value"]
                      for p in ET.parse(report).getroot().findall("properties/property")}
        classpath = properties.get("surefire.test.class.path", properties.get("java.class.path"))
        source = properties.get("maven.compiler.release", properties.get(
            "maven.compiler.source", properties.get("java.specification.version")))
        if classpath and source:
            return {"sourceLevel": source, "classpath": classpath.split(os.pathsep) + [
                str(root / "target/classes"), str(root / "target/test-classes")]}
    raise RuntimeError("Run mvn test (or mvn verify) first to generate Maven dependency and source-level metadata.")


def request_result(method, params, config):
    if method == "workspace/configuration":
        results = []
        for item in params["items"]:
            value = {"sonarlint": config["settings"]}
            for part in item.get("section", "").split("."):
                if part:
                    value = value.get(part, {}) if isinstance(value, dict) else {}
            results.append(value)
        return results
    if method == "sonarlint/getJavaConfig":
        uri = params[0] if isinstance(params, list) else params
        return {"projectRoot": str(config["root"]), "sourceLevel": config["sourceLevel"],
                "classpath": config["classpath"], "isTest": "/src/test/" in uri,
                "vmLocation": config["javaHome"]}
    if method == "sonarlint/listFilesInFolder":
        return {"foundFiles": []}
    if method == "sonarlint/filterOutExcludedFiles":
        return {"fileUris": params["fileUris"]}
    if method in ("sonarlint/isOpenInEditor", "sonarlint/shouldAnalyseFile"):
        return True
    if method in ("sonarlint/isIgnoredByScm", "sonarlint/canShowMissingRequirementsNotification",
                  "sonarlint/askSslCertificateConfirmation"):
        return False
    if method in ("client/registerCapability", "window/workDoneProgress/create"):
        return None
    raise RuntimeError(f"Unsupported language-server request: {method}")


def read_messages(stream, messages):
    try:
        while True:
            headers = {}
            while True:
                line = stream.readline()
                if not line:
                    raise EOFError("Language server closed stdout.")
                if line == b"\r\n":
                    break
                key, value = line.decode("ascii").split(":", 1)
                headers[key.lower()] = value.strip()
            length = int(headers["content-length"])
            data = bytearray()
            while len(data) < length:
                chunk = stream.read(length - len(data))
                if not chunk:
                    raise EOFError("Incomplete language-server response.")
                data.extend(chunk)
            messages.put(json.loads(data))
    except (OSError, EOFError, ValueError, KeyError) as error:
        messages.put({"failure": str(error)})


def analyze(config, texts, extension, java, timeout, log):
    process = subprocess.Popen(
        [java, "-Dsonarlint.telemetry.disabled=true", "-jar", str(extension / "server/sonarlint-ls.jar"),
         "-stdio", "-analyzers=" + str(extension / "analyzers/sonarjava.jar")],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log)
    messages = queue.Queue()
    reader = threading.Thread(target=read_messages, args=(process.stdout, messages), daemon=True)
    reader.start()
    settings = config["settings"]
    root = config["root"]
    files = list(texts)

    def send(payload):
        data = json.dumps({"jsonrpc": "2.0", **payload}).encode("utf-8")
        process.stdin.write(f"Content-Length: {len(data)}\r\n\r\n".encode("ascii") + data)
        process.stdin.flush()

    def notify(method, params):
        send({"method": method, "params": params})

    def open_file(file):
        notify("textDocument/didOpen", {"textDocument": {
            "uri": file.as_uri(), "languageId": "java", "version": 1, "text": texts[file]}})

    try:
        send({"id": 1, "method": "initialize", "params": {
            "processId": os.getpid(), "rootUri": root.as_uri(),
            "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}],
            "capabilities": {"workspace": {"configuration": True, "workspaceFolders": True},
                             "textDocument": {"publishDiagnostics": {"relatedInformation": True}}},
            "initializationOptions": {
                "productKey": "vscode", "productName": "Local Sonar preflight",
                "productVersion": json.loads((extension / "package.json").read_text(encoding="utf-8"))["version"],
                "workspaceName": root.name, "showVerboseLogs": True, "additionalAttributes": {},
                "automaticAnalysis": True, "rules": settings["rules"],
                "connections": {"sonarqube": [], "sonarcloud": []}}}})
        diagnostics = {}
        deadline = time.monotonic() + timeout
        while len(diagnostics) < len(files):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f"Analysis timed out after {timeout} seconds.")
            try:
                message = messages.get(timeout=min(remaining, 5))
            except queue.Empty:
                continue
            log.write((json.dumps(message) + "\n").encode("utf-8"))
            log.flush()
            if "failure" in message:
                raise RuntimeError(message["failure"])
            if "method" in message and "id" in message:
                send({"id": message["id"], "result": request_result(
                    message["method"], message.get("params"), config)})
            elif message.get("id") == 1:
                if "error" in message:
                    raise RuntimeError(f"Initialization failed: {message['error']}")
                notify("initialized", {})
                open_file(files[0])
            elif message.get("method") == "window/logMessage":
                text = message["params"]["message"]
                if any(marker in text for marker in (
                        "Unresolved imports/types", "[Error", "Unable to parse", "Parse error", "Analysis failed")):
                    raise RuntimeError(f"Incomplete analysis: {text[:1000]}")
            elif message.get("method") == "textDocument/publishDiagnostics":
                params = message["params"]
                expected = files[len(diagnostics)].as_uri()
                if params["uri"] == expected:
                    diagnostics[expected] = params["diagnostics"]
                    # The server needs Java metadata cached before scheduling the next file.
                    if len(diagnostics) < len(files):
                        open_file(files[len(diagnostics)])
        return diagnostics
    finally:
        if process.poll() is None:
            try:
                send({"id": 2, "method": "shutdown", "params": None})
                notify("exit", None)
                process.wait(timeout=10)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        reader.join(timeout=5)
        process.stdin.close()
        process.stdout.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", help="Java files relative to the current directory.")
    parser.add_argument("--extension", type=Path, help="SonarQube for IDE extension directory.")
    parser.add_argument("--java", help="Java executable; defaults to JAVA_HOME/bin/java or PATH.")
    parser.add_argument("--ref", help="Analyze text from a Git revision instead of the working tree.")
    parser.add_argument("--timeout", type=int, default=180, help="Overall analysis timeout in seconds.")
    parser.add_argument("--output", type=Path, help="Write diagnostics as JSON.")
    args = parser.parse_args()
    try:
        if args.timeout <= 0:
            raise RuntimeError("--timeout must be positive.")
        root = Path(subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"], text=True).strip()).resolve()
        extension = args.extension.expanduser().resolve() if args.extension else discover_extension(Path.home())
        if not complete_extension(extension):
            raise RuntimeError(f"Missing language server or Java analyzer in {extension}.")
        java = args.java
        if not java:
            executable = "java.exe" if os.name == "nt" else "java"
            java = str(Path(os.environ["JAVA_HOME"]) / "bin" / executable) if os.environ.get(
                "JAVA_HOME") else shutil.which(executable)
        if not java:
            raise RuntimeError("Install a JDK supported by SonarQube for IDE, or specify --java.")
        java_info = subprocess.run([java, "-XshowSettings:properties", "-version"],
                                   check=True, capture_output=True, text=True)
        home = re.search(r"^\s*java.home\s*=\s*(.+)$", java_info.stderr, re.MULTILINE)
        if not home:
            raise RuntimeError("Cannot determine the Java home directory.")
        config = {**maven_config(root), "root": root, "javaHome": home[1].strip()}
        workspace = root / ".vscode/settings.json"
        local = json.loads(workspace.read_text(encoding="utf-8")) if workspace.exists() else {}
        config["settings"] = {
            "disableTelemetry": True, "rules": local.get("sonarlint.rules", {}),
            "testFilePattern": "**/test/**", "output": {"showVerboseLogs": True},
            "connectedMode": {"connections": {}}, "automaticAnalysis": True}
        texts = {}
        for name in args.files:
            file = Path(name).resolve()
            relative = file.relative_to(root)
            if file.suffix != ".java":
                raise RuntimeError(f"Not a Java file: {name}")
            texts[file] = (subprocess.check_output(
                ["git", "show", f"{args.ref}:{relative.as_posix()}"], cwd=root, text=True)
                if args.ref else file.read_text(encoding="utf-8"))
        with tempfile.TemporaryFile() as log:
            try:
                diagnostics = analyze(config, texts, extension, java, args.timeout, log)
            except (RuntimeError, OSError):
                log.seek(0)
                print(log.read().decode("utf-8", errors="replace")[-6000:], file=sys.stderr)
                raise
        if args.output:
            args.output.write_text(json.dumps(diagnostics, indent=2) + "\n", encoding="utf-8")
        count = 0
        for uri, findings in diagnostics.items():
            print(f"{uri}: {len(findings)} finding(s)")
            for finding in findings:
                print(f"  {finding.get('code')}:L{finding['range']['start']['line'] + 1} {finding['message']}")
            count += len(findings)
        return 1 if count else 0
    except (RuntimeError, OSError, ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
        print(f"Local Sonar analysis failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
