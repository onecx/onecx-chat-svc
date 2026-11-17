import io
import json
from pathlib import Path
import queue
import tempfile
import unittest

from sonar_local import discover_extension, maven_config, read_messages, request_result


class SonarLocalTest(unittest.TestCase):
    def test_discovers_newest_complete_extension_numerically(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            for version in ("4.9.0", "4.33.0", "5.0.0"):
                extension = home / ".vscode-server/extensions" / f"sonarsource.sonarlint-vscode-{version}"
                (extension / "server").mkdir(parents=True)
                (extension / "server/sonarlint-ls.jar").touch()
                if version != "5.0.0":
                    (extension / "analyzers").mkdir()
                    (extension / "analyzers/sonarjava.jar").touch()
            self.assertEqual(discover_extension(home).name, "sonarsource.sonarlint-vscode-4.33.0")

    def test_missing_extension_reports_prerequisite(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "SonarQube for IDE"):
                discover_extension(Path(directory))

    def test_reads_maven_dependencies_and_source_level(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "target/surefire-reports"
            reports.mkdir(parents=True)
            (reports / "TEST-example.xml").write_text(
                '<testsuite><properties>'
                '<property name="java.class.path" value="/example/classes"/>'
                '<property name="java.specification.version" value="21"/>'
                '</properties></testsuite>', encoding="utf-8")
            config = maven_config(root)
            self.assertEqual(config["sourceLevel"], "21")
            self.assertIn("/example/classes", config["classpath"])
            self.assertIn(str(root / "target/test-classes"), config["classpath"])

    def test_missing_build_report_is_not_a_successful_empty_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "mvn"):
                maven_config(Path(directory))

    def test_java_config_handles_lsp_positional_params_and_test_classpath(self):
        config = {"root": Path("/project"), "javaHome": "/jdk", "sourceLevel": "21",
                  "classpath": ["/dependencies/junit.jar"], "settings": {}}
        result = request_result(
            "sonarlint/getJavaConfig", ["file:///project/src/test/java/ExampleTest.java"], config)
        self.assertTrue(result["isTest"])
        self.assertEqual(result["classpath"], ["/dependencies/junit.jar"])
        main = request_result("sonarlint/getJavaConfig", ["file:///project/src/main/java/Example.java"], config)
        self.assertFalse(main["isTest"])

    def test_unknown_server_request_fails_explicitly(self):
        with self.assertRaisesRegex(RuntimeError, "Unsupported"):
            request_result("sonarlint/futureProtocol", {}, {})

    def test_reads_framed_utf8_diagnostics(self):
        body = json.dumps({"method": "diagnostics", "message": "\u00e4"}, ensure_ascii=False).encode("utf-8")
        stream = io.BytesIO(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body)
        messages = queue.Queue()
        read_messages(stream, messages)
        self.assertEqual(messages.get_nowait()["message"], "\u00e4")
        self.assertIn("failure", messages.get_nowait())

    def test_truncated_server_response_is_reported_as_failure(self):
        messages = queue.Queue()
        read_messages(io.BytesIO(b"Content-Length: 100\r\n\r\n{}"), messages)
        self.assertIn("Incomplete", messages.get_nowait()["failure"])


if __name__ == "__main__":
    unittest.main()
