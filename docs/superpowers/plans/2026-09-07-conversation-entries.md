# Conversation Entries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new, sequence-ordered `ConversationEntry` persistence model and internal REST API to `onecx-chat-svc` so an authenticated internal runtime can durably write finalized human speech, cumulative assistant checkpoints, and terminal outcomes per chat, with idempotent upsert semantics, stable per-chat sequencing, monotonic checkpoint text, immutable terminal status, and tenant/chat authorized replay — without touching the existing `Message` model or its API.

**Architecture:** Follow the existing `onecx-chat-svc` layering exactly: a new JPA entity (`ConversationEntry`) in `domain/models`, a DAO (`ConversationEntryDAO`) in `domain/daos` extending `org.tkit.quarkus.jpa.daos.AbstractDAO`, a criteria class in `domain/criteria`, a service class (`ConversationEntryService`) in `rs/internal/services` holding all idempotency/sequence/conflict business logic inside `@Transactional` methods, a MapStruct mapper (`ConversationEntryMapper`), and a JAX-RS controller (`ConversationEntriesRestController`) implementing a new generated API interface produced from an OpenAPI addition to `src/main/openapi/onecx-chat-internal-openapi.yaml`. Persistence is Postgres via Liquibase changesets appended to `src/main/resources/db/changeLog.xml`. Per-chat sequence allocation and idempotency are enforced at the database level using a unique constraint on `(chat_id, idempotency_key)` plus a unique constraint on `(chat_id, sequence_number)`, with sequence numbers assigned by the service reading `MAX(sequence_number)` for the chat inside the entry's own transaction. Multi-tenancy reuses the existing Hibernate `@TenantId` discriminator pattern already used by `Chat`, `Message`, and `Participant`.

**Tech Stack:** Java 17, Quarkus 3 (parent `onecx-quarkus3-parent` 3.4.0), Jakarta Persistence (Hibernate ORM, `quarkus-hibernate-orm`), Liquibase XML changesets, MapStruct, JAX-RS (RESTEasy Reactive) with `jaxrs-spec` OpenAPI-generated interfaces, JUnit 5 + `@QuarkusTest` + REST Assured + `tkit-quarkus-test-db-import` (`@WithDBData`) + `tkit-quarkus-security-test` (`@GenerateKeycloakClient`), Antora/AsciiDoc documentation under `docs/modules/onecx-chat-svc/pages/`.

**Spec:** GitHub issue onecx/internal-tasks#591 "Expand chat persistence with Conversation Entries" (full text embedded in this plan's originating task — no separate spec file exists in-repo).

## Global Constraints

- Do not modify the existing `Message` entity, `MessageDAO`, `ChatsRestController` message endpoints, or the existing `Message`/`CreateMessage` OpenAPI schemas — Conversation Entries are additive and must not break typed chat.
- All new JPA entities extend `org.tkit.quarkus.jpa.models.TraceableEntity` and use `@TenantId` on a `tenantId` column exactly like `Chat`, `Message`, `Participant` do, to keep `quarkus.hibernate-orm.multitenant=DISCRIMINATOR` tenant isolation intact.
- All new DAOs extend `org.tkit.quarkus.jpa.daos.AbstractDAO<T>` and wrap query logic in try/catch rethrowing `org.tkit.quarkus.jpa.exceptions.DAOException` with an `ErrorKeys` enum, matching `MessageDAO`/`ChatDAO`/`ParticipantDAO`.
- All new Liquibase changesets are added as new files under `src/main/resources/db/v1/` (never edit existing changesets) and are registered via `<include>` in `src/main/resources/db/changeLog.xml`, matching the `2026-02-09-create-tables.xml` / `2026-03-03-increase-message-size.xml` pattern.
- All new REST endpoints are defined in `src/main/openapi/onecx-chat-internal-openapi.yaml` (the only OpenAPI generation source configured in `pom.xml`), secured with the existing `oauth2` security scheme, and use `DTO` model name suffix conventions consistent with existing schemas.
- All new JAX-RS controllers are `@ApplicationScoped`, `@Transactional(value = NOT_SUPPORTED)` at the class level (delegating actual transactions to service classes), matching `ChatsRestController`.
- All new tests use `@QuarkusTest`, `@TestHTTPEndpoint`, `@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)`, and `@GenerateKeycloakClient` exactly like existing controller tests, and extend `org.tkit.onecx.chat.test.AbstractTest` for tenant token helpers (`createToken`, `APM_HEADER_PARAM`).
- Tenant isolation tests use the existing `%test.tkit.rs.context.tenant-id.mock.data.org1=tenant-100` / `org2=tenant-200` mapping already configured in `src/main/resources/application.properties`.

---

## Problem Statement

`onecx-chat-svc` currently persists chat turns only as flat `Message` rows (`Chat` 1--N `Message`, ordered by `creationDate`, typed as `SYSTEM`/`HUMAN`/`ASSISTANT`). This model has no concept of: (1) an idempotency key so a retried write from an AI runtime does not create duplicate rows, (2) a stable per-chat sequence number that is assigned once and never changes on update, (3) incrementally growing ("cumulative") assistant checkpoint text where retries with identical text are no-ops but retries with incompatible (non-prefix) text fail as a conflict, (4) an explicit terminal/non-terminal status distinction (`IN_PROGRESS` hidden from replay vs. immutable terminal `COMPLETED`/`INTERRUPTED`), or (5) a "replay" read API that returns only terminal, tenant/chat-authorized entries in sequence order. The end state adds a new, independent `ConversationEntry` persistence model and internal REST API (create-or-update via idempotency key, and replay-by-chat) that satisfies all six acceptance criteria from issue #591, is fully covered by Quarkus/JUnit persistence and controller tests, and is documented in Antora docs — while leaving the existing `Message` model, its DAO, mapper, controller endpoints, and OpenAPI schemas completely untouched.

## Approach

1. **Data model.** Introduce `ConversationEntry` as a new `TraceableEntity` subtype (own table `CONVERSATION_ENTRY`), linked to `Chat` via a `@ManyToOne` `chat` association (mirroring `Message.chat`), carrying:
   - `tenantId` (`@TenantId`, mirrors existing entities)
   - `idempotencyKey` (`VARCHAR(255)`, not null) — client-supplied key for the logical entry
   - `sequenceNumber` (`BIGINT`, not null) — server-assigned on first write, immutable afterward
   - `entryType` (`VARCHAR`, enum `HUMAN_UTTERANCE`, `ASSISTANT_CHECKPOINT`, `TERMINAL_OUTCOME`) — distinguishes the three payload kinds named in the issue ("finalized human speech, cumulative assistant checkpoints, and terminal outcomes")
   - `text` (`VARCHAR(10000)`, nullable) — cumulative text payload for human/assistant entries
   - `status` (`VARCHAR`, enum `IN_PROGRESS`, `COMPLETED`, `INTERRUPTED`) — `IN_PROGRESS` is the only non-terminal, hidden status; `COMPLETED`/`INTERRUPTED` are terminal and immutable once set
   - `userId` (`VARCHAR(255)`, nullable) — actor identifier, mirrors `Message.userId`

   A unique DB constraint on `(chat_id, idempotency_key)` enforces "create-or-update using an idempotency key" at the storage layer. A unique DB constraint on `(chat_id, sequence_number)` enforces sequence stability/uniqueness per chat.

2. **Sequence allocation.** On first write for a given `(chatId, idempotencyKey)`, the service queries `MAX(sequenceNumber)` for the chat (via DAO) inside its own `@Transactional` method and assigns `max + 1` (or `1` if none exist), then persists. On every subsequent call with the same `(chatId, idempotencyKey)`, the existing row's `sequenceNumber` is read and reused untouched — the service never overwrites `sequenceNumber` on update.

3. **Idempotency / cumulative monotonic checkpoint semantics.** The service looks up the existing entry by `(chatId, idempotencyKey)`. When absent, it creates a new row (status defaults to `IN_PROGRESS` unless the payload explicitly supplies a terminal status). When present:
   - When the entry status is already terminal (`COMPLETED`/`INTERRUPTED`), any further write is rejected as a conflict (`409`) — terminal is immutable.
   - When the request text is exactly equal to the stored text (identical retry) and the requested status equals the stored status, the call is a no-op (return current state, `200`, no DB write).
   - When the request text starts with the stored text as a prefix and is longer (monotonic growth), the row is updated with the new (longer) text — "cumulative" growth.
   - When the request text is not equal to and does not extend (as a prefix) the stored text, the call is an "incompatible retry" and is rejected as a conflict (`409`).
   - A request that sets `status` to `COMPLETED` or `INTERRUPTED` finalizes the row; this transition from `IN_PROGRESS` to a terminal status is allowed exactly once per entry, and any subsequent write to that idempotency key is rejected as a conflict per the immutability rule above.

4. **Replay.** A new read endpoint returns all `ConversationEntry` rows for a chat where `status IN (COMPLETED, INTERRUPTED)`, ordered by `sequenceNumber ASC`, excluding `IN_PROGRESS` rows entirely (hidden). Authorization: the `chatId` must resolve to a `Chat` owned by the caller's tenant (reusing `ChatDAO.findById`, which already tenant-scopes via Hibernate's tenant discriminator filter) — when the chat does not exist for the caller's tenant, the endpoint returns `404`, mirroring `ChatsRestController.getChatMessages`.

5. **Authorization surface.** Reuse the existing `oauth2` security scheme and add two new scopes, `ocx-chat:conversation-entries:write` and `ocx-chat:conversation-entries:read` (independent from the existing `ocx-chat:*` message/chat scopes), so the "authenticated internal runtime" caller is a distinct, explicitly scoped internal client, while tenant isolation is enforced transparently by the existing Hibernate multitenancy discriminator (`quarkus.hibernate-orm.multitenant=DISCRIMINATOR`) exactly as for `Chat`/`Message`.

6. **Testing.** Add DAO-level tests (`ConversationEntryDAOTest`) mirroring `MessageDAOTest`'s exception-path style, DAO persistence tests against real fixture data, service-level tests (`ConversationEntryServiceTest`) covering sequence stability, idempotent no-op, monotonic growth, incompatible conflict, and terminal immutability, and controller-level tests (`ConversationEntriesRestControllerTest` plus a tenant variant `ConversationEntriesRestControllerTenantTest`) covering full HTTP flows including cross-tenant `404`s, matching `ChatsRestControllerTest` / `ChatsRestControllerTenantTest` conventions. Add fixture rows to `testdata-internal.xml`.

7. **Documentation.** Add a new Antora page describing the Conversation Entry lifecycle (states, sequence allocation, idempotency, monotonic checkpoint rule, terminal immutability) and the replay contract (ordering, visibility filtering, authorization), linked from the existing `index.adoc`.

## File-Level Task List

### 1. `src/main/openapi/onecx-chat-internal-openapi.yaml`
- **Action:** modify
- **Summary:** Add two new paths and their schemas, without touching any existing path or schema.
  - New tag `conversationEntriesInternal` added to the top-level `tags:` list.
  - New path `/internal/chats/{chatId}/conversation-entries`:
    - `POST`: `operationId: createOrUpdateConversationEntry`, security `oauth2: [ ocx-chat:all, ocx-chat:conversation-entries:write ]`, tag `conversationEntriesInternal`, path param `chatId` (string, required), request body `$ref: '#/components/schemas/CreateOrUpdateConversationEntry'`, responses: `200` (no-op or updated, body `$ref: '#/components/schemas/ConversationEntry'`), `201` (created, body `$ref: '#/components/schemas/ConversationEntry'`, `Location` header), `404` (chat not found, `$ref: '#/components/schemas/ProblemDetailResponse'`), `409` (conflict — incompatible retry or terminal immutability violation, `$ref: '#/components/schemas/ProblemDetailResponse'`), `400` (bad request, `$ref: '#/components/schemas/ProblemDetailResponse'`).
    - `GET`: `operationId: getConversationEntries`, security `oauth2: [ ocx-chat:all, ocx-chat:conversation-entries:read ]`, tag `conversationEntriesInternal`, path param `chatId` (string, required), response `200` body `type: array items: $ref: '#/components/schemas/ConversationEntry'` (replay endpoint — terminal-only, sequence-ordered), `404` when chat not found for tenant.
  - New schemas under `components/schemas`:
    - `ConversationEntryType`: `type: string, enum: [HUMAN_UTTERANCE, ASSISTANT_CHECKPOINT, TERMINAL_OUTCOME]`
    - `ConversationEntryStatus`: `type: string, enum: [IN_PROGRESS, COMPLETED, INTERRUPTED]`
    - `ConversationEntry`: object with `version` (int32, from `modificationCount`), `creationDate`/`creationUser`/`modificationDate`/`modificationUser` (same shape as `Message`), `id` (string), `chatId` (string), `idempotencyKey` (string), `sequenceNumber` (format `int64`, type `integer`), `entryType` (`$ref: '#/components/schemas/ConversationEntryType'`), `text` (string), `status` (`$ref: '#/components/schemas/ConversationEntryStatus'`), `userId` (string).
    - `CreateOrUpdateConversationEntry`: required `[idempotencyKey, entryType]`, properties: `idempotencyKey` (string), `entryType` (`$ref: '#/components/schemas/ConversationEntryType'`), `text` (string), `status` (`$ref: '#/components/schemas/ConversationEntryStatus'`), `userId` (string).
  - Add new scopes `ocx-chat:conversation-entries:write: Grants write access to conversation entries` and `ocx-chat:conversation-entries:read: Grants read access to conversation entries` to the existing `oauth2` security scheme `scopes` map.
- **Concrete TODOs:**
  1. Insert `- name: conversationEntriesInternal` into the top-level `tags:` list, directly after the existing `- name: messagesInternal` line.
  2. Insert the new path block (`/internal/chats/{chatId}/conversation-entries` with `post` and `get` operations as specified above) under `paths:`, directly after the existing `/internal/chats/{chatId}/participants` path block.
  3. Add the two new scopes to `components.securitySchemes.oauth2.flows.clientCredentials.scopes`, directly after the existing `ocx-chat:delete` scope line.
  4. Add the four new schemas (`ConversationEntryType`, `ConversationEntryStatus`, `ConversationEntry`, `CreateOrUpdateConversationEntry`) under `components.schemas`, directly after the existing `Message` schema.
- **Dependencies:** none.

### 2. `src/main/java/org/tkit/onecx/chat/domain/models/ConversationEntry.java`
- **Action:** create
- **Summary:** New JPA entity mirroring `Message.java`'s style (Lombok `@Getter`/`@Setter`, `@Entity`, `@Table(name = "CONVERSATION_ENTRY")`, extends `TraceableEntity`, `@TenantId` on `tenantId`), with fields `idempotencyKey`, `sequenceNumber`, `entryType`, `text`, `status`, `userId`, and `chat`. Nested enums `EntryType` and `Status`.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.domain.models`, imports mirroring `Message.java` (`static jakarta.persistence.FetchType.LAZY`, `jakarta.persistence.*`, `org.hibernate.annotations.TenantId`, `org.tkit.quarkus.jpa.models.TraceableEntity`, `lombok.Getter`, `lombok.Setter`).
  2. Add class-level annotations `@Getter @Setter @Entity @Table(name = "CONVERSATION_ENTRY")` on `public class ConversationEntry extends TraceableEntity`.
  3. Add field `@TenantId @Column(name = "TENANT_ID") private String tenantId;`.
  4. Add field `@Column(name = "IDEMPOTENCY_KEY", length = 255, nullable = false) private String idempotencyKey;`.
  5. Add field `@Column(name = "SEQUENCE_NUMBER", nullable = false) private Long sequenceNumber;`.
  6. Add field `@Column(name = "ENTRY_TYPE") @Enumerated(EnumType.STRING) private EntryType entryType;`.
  7. Add field `@Column(name = "TEXT", length = 10000) private String text;`.
  8. Add field `@Column(name = "STATUS") @Enumerated(EnumType.STRING) private Status status = Status.IN_PROGRESS;`.
  9. Add field `@Column(name = "USER_ID") private String userId;`.
  10. Add field `@ManyToOne(fetch = LAZY) @JoinColumn(name = "CHAT_ID") private Chat chat;`.
  11. Add `public enum EntryType { HUMAN_UTTERANCE, ASSISTANT_CHECKPOINT, TERMINAL_OUTCOME }`.
  12. Add `public enum Status { IN_PROGRESS, COMPLETED, INTERRUPTED }`.
- **Dependencies:** Task 1.

### 3. `src/main/resources/db/v1/2026-09-07-create-conversation-entry-table.xml`
- **Action:** create
- **Summary:** New Liquibase changeset file that creates the `conversation_entry` table with columns matching the entity, a foreign key to `chat`, and the two required unique constraints.
- **Concrete TODOs:**
  1. Create the file with the same XML header/namespace as `2026-02-09-create-tables.xml` (`<?xml version="1.1" encoding="UTF-8" standalone="no"?>` root `databaseChangeLog` element with `objectQuotingStrategy="QUOTE_ONLY_RESERVED_WORDS"`).
  2. Add changeset `id="1770646801172-8" author="dev (generated)"` containing `<createTable tableName="conversation_entry">` with columns: `optlock INTEGER NOT NULL`, `creationdate TIMESTAMP WITHOUT TIME ZONE`, `modificationdate TIMESTAMP WITHOUT TIME ZONE`, `chat_id VARCHAR(255)`, `guid VARCHAR(255) NOT NULL PRIMARY KEY` (`primaryKeyName="conversation_entry_pkey"`), `tenant_id VARCHAR(255) NOT NULL`, `idempotency_key VARCHAR(255) NOT NULL`, `sequence_number BIGINT NOT NULL`, `entry_type VARCHAR(255)`, `text VARCHAR(10000)`, `status VARCHAR(255)`, `user_id VARCHAR(255)`, `creationuser VARCHAR(255)`, `modificationuser VARCHAR(255)`.
  3. Add changeset `id="1770646801172-9"` with `<addForeignKeyConstraint baseColumnNames="chat_id" baseTableName="conversation_entry" constraintName="fkconventrychat" deferrable="false" initiallyDeferred="false" onDelete="CASCADE" onUpdate="NO ACTION" referencedColumnNames="guid" referencedTableName="chat" validate="true"/>`.
  4. Add changeset `id="1770646801172-10"` with `<addUniqueConstraint columnNames="chat_id, idempotency_key" constraintName="uq_conversation_entry_chat_idempotency" tableName="conversation_entry"/>`.
  5. Add changeset `id="1770646801172-11"` with `<addUniqueConstraint columnNames="chat_id, sequence_number" constraintName="uq_conversation_entry_chat_sequence" tableName="conversation_entry"/>`.
- **Dependencies:** none.

### 4. `src/main/resources/db/changeLog.xml`
- **Action:** modify
- **Summary:** Register the new changeset file so Liquibase applies it on startup (`quarkus.liquibase.migrate-at-start=true`).
- **Concrete TODOs:**
  1. Add the line `<include file="v1/2026-09-07-create-conversation-entry-table.xml" relativeToChangelogFile="true" />` directly after the existing `<include file="v1/2026-03-03-increase-message-size.xml" relativeToChangelogFile="true" />` line.
- **Dependencies:** Task 3.

### 5. `src/main/java/org/tkit/onecx/chat/domain/criteria/ConversationEntryReplayCriteria.java`
- **Action:** create
- **Summary:** New criteria POJO carrying the `chatId` for the replay query, mirroring `ChatMessageSearchCriteria.java`'s style, without pagination fields, because replay returns the full ordered terminal set per acceptance criterion "Replay returns visible terminal entries in sequence order".
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.domain.criteria`, `@Getter @Setter @RegisterForReflection` class `ConversationEntryReplayCriteria` with a single field `private String chatId;`.
- **Dependencies:** none.

### 6. `src/main/java/org/tkit/onecx/chat/domain/daos/ConversationEntryDAO.java`
- **Action:** create
- **Summary:** New DAO extending `AbstractDAO<ConversationEntry>`, `@ApplicationScoped`, `@Transactional(Transactional.TxType.SUPPORTS)` at class level (matching `MessageDAO`), with three methods: `findByChatIdAndIdempotencyKey`, `findMaxSequenceNumberForChat`, `findVisibleTerminalEntriesForChat`.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.domain.daos`, imports mirroring `MessageDAO.java` plus `java.util.List`, `java.util.Optional`, `jakarta.enterprise.context.ApplicationScoped`, `jakarta.persistence.criteria.Predicate`, `jakarta.transaction.Transactional`, `org.tkit.onecx.chat.domain.models.ConversationEntry`, `org.tkit.onecx.chat.domain.models.ConversationEntry_`, `org.tkit.quarkus.jpa.daos.AbstractDAO`, `org.tkit.quarkus.jpa.exceptions.DAOException`, `org.tkit.quarkus.jpa.models.TraceableEntity_`.
  2. Add `@ApplicationScoped @Transactional(Transactional.TxType.SUPPORTS) public class ConversationEntryDAO extends AbstractDAO<ConversationEntry>`.
  3. Implement `public Optional<ConversationEntry> findByChatIdAndIdempotencyKey(String chatId, String idempotencyKey)`: build a `CriteriaQuery<ConversationEntry>`, `root = cq.from(ConversationEntry.class)`, predicates `cb.equal(root.get(ConversationEntry_.CHAT).get(TraceableEntity_.ID), chatId)` and `cb.equal(root.get(ConversationEntry_.IDEMPOTENCY_KEY), idempotencyKey)`, `cq.where(cb.and(predicates.toArray(new Predicate[0])))`, execute via `getEntityManager().createQuery(cq).getResultList()`, return `results.isEmpty() ? Optional.empty() : Optional.of(results.get(0))`, wrap exceptions with `throw new DAOException(ErrorKeys.ERROR_FIND_BY_CHAT_AND_IDEMPOTENCY_KEY, ex)`.
  4. Implement `public Long findMaxSequenceNumberForChat(String chatId)`: build `CriteriaQuery<Long>`, `cq.select(cb.max(root.get(ConversationEntry_.SEQUENCE_NUMBER)))`, `cq.where(cb.equal(root.get(ConversationEntry_.CHAT).get(TraceableEntity_.ID), chatId))`, execute `getEntityManager().createQuery(cq).getSingleResult()`, return `0L` when the query result is `null`, wrap exceptions with `throw new DAOException(ErrorKeys.ERROR_FIND_MAX_SEQUENCE_NUMBER, ex)`.
  5. Implement `public List<ConversationEntry> findVisibleTerminalEntriesForChat(String chatId)`: build `CriteriaQuery<ConversationEntry>`, predicates `cb.equal(root.get(ConversationEntry_.CHAT).get(TraceableEntity_.ID), chatId)` and `root.get(ConversationEntry_.STATUS).in(List.of(ConversationEntry.Status.COMPLETED, ConversationEntry.Status.INTERRUPTED))`, `cq.where(cb.and(predicates.toArray(new Predicate[0])))`, `cq.orderBy(cb.asc(root.get(ConversationEntry_.SEQUENCE_NUMBER)))`, execute `getEntityManager().createQuery(cq).getResultList()`, wrap exceptions with `throw new DAOException(ErrorKeys.ERROR_FIND_VISIBLE_TERMINAL_ENTRIES, ex)`.
  6. Add `public enum ErrorKeys { ERROR_FIND_BY_CHAT_AND_IDEMPOTENCY_KEY, ERROR_FIND_MAX_SEQUENCE_NUMBER, ERROR_FIND_VISIBLE_TERMINAL_ENTRIES }`.
- **Dependencies:** Task 2 (the `ConversationEntry_` static metamodel class is generated at build time by the same Hibernate JPA metamodel annotation processor already configured in the parent POM that generates `Message_`/`Chat_`).

### 7. `src/main/java/org/tkit/onecx/chat/rs/internal/mappers/ConversationEntryMapper.java`
- **Action:** create
- **Summary:** New MapStruct mapper interface for `ConversationEntry` to/from DTOs, following `ChatMapper.java`'s conventions (map `version` from `modificationCount`, flatten the nested `chat` association to `chatId`).
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.rs.internal.mappers`, imports `java.util.List`, `org.mapstruct.Mapper`, `org.mapstruct.Mapping`, `org.tkit.onecx.chat.domain.models.ConversationEntry`, `org.tkit.quarkus.rs.mappers.OffsetDateTimeMapper`, `gen.org.tkit.onecx.chat.rs.internal.model.ConversationEntryDTO`.
  2. Declare `@Mapper(uses = { OffsetDateTimeMapper.class }) public interface ConversationEntryMapper`.
  3. Add method `@Mapping(target = "chatId", source = "chat.id") @Mapping(target = "version", source = "modificationCount") ConversationEntryDTO map(ConversationEntry entity);`.
  4. Add method `List<ConversationEntryDTO> mapList(List<ConversationEntry> entities);`.
- **Dependencies:** Task 1, Task 2.

### 8. `src/main/java/org/tkit/onecx/chat/rs/internal/services/ConversationEntryService.java`
- **Action:** create
- **Summary:** New `@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)` service class (mirroring `ChatsService.java`'s class-level annotation style) holding the create-or-update business logic and the replay read logic. Injects `ConversationEntryDAO`.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.rs.internal.services`, imports `java.util.List`, `jakarta.enterprise.context.ApplicationScoped`, `jakarta.inject.Inject`, `jakarta.transaction.Transactional`, `org.tkit.onecx.chat.domain.daos.ConversationEntryDAO`, `org.tkit.onecx.chat.domain.models.Chat`, `org.tkit.onecx.chat.domain.models.ConversationEntry`, `gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO`, `lombok.AllArgsConstructor`, `lombok.Getter`, `lombok.extern.slf4j.Slf4j`.
  2. Add a `public static class ConversationEntryConflictException extends RuntimeException` nested inside `ConversationEntryService`, with constructor `public ConversationEntryConflictException(String message) { super(message); }`.
  3. Add a `@Getter @AllArgsConstructor public static class ConversationEntryOutcome` nested inside `ConversationEntryService`, with fields `private final ConversationEntry entry;` and `private final boolean created;`.
  4. Declare `@Slf4j @ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED) public class ConversationEntryService` with `@Inject ConversationEntryDAO dao;`.
  5. Implement `@Transactional public ConversationEntryOutcome createOrUpdate(Chat chat, CreateOrUpdateConversationEntryDTO dto)`:
     - Look up `var existing = dao.findByChatIdAndIdempotencyKey(chat.getId(), dto.getIdempotencyKey());`.
     - When `existing.isEmpty()`: build a new `ConversationEntry`, set `chat`, `idempotencyKey = dto.getIdempotencyKey()`, `entryType = ConversationEntry.EntryType.valueOf(dto.getEntryType().name())`, `text = dto.getText()`, `userId = dto.getUserId()`, `status = dto.getStatus() != null ? ConversationEntry.Status.valueOf(dto.getStatus().name()) : ConversationEntry.Status.IN_PROGRESS`; compute `sequenceNumber = dao.findMaxSequenceNumberForChat(chat.getId()) + 1`; persist via `dao.create(entry)`; return `new ConversationEntryOutcome(entry, true)`.
     - When `existing.isPresent()` and `existing.get().getStatus() != ConversationEntry.Status.IN_PROGRESS`: throw `new ConversationEntryConflictException("Entry is already finalized with terminal status " + existing.get().getStatus())`.
     - When `existing.isPresent()` and status is `IN_PROGRESS`: compute `requestedText = dto.getText() == null ? "" : dto.getText()` and `existingText = existing.get().getText() == null ? "" : existing.get().getText()`; compute `requestedStatus` from `dto.getStatus()` (null keeps `IN_PROGRESS`).
       - When `requestedText.equals(existingText)` and the requested status equals the existing status: return `new ConversationEntryOutcome(existing.get(), false)` without calling `dao.update`.
       - When `requestedText.startsWith(existingText)` (and is not equal, or the status changes): set `existing.get().setText(dto.getText())`; when `dto.getStatus() != null` set `existing.get().setStatus(ConversationEntry.Status.valueOf(dto.getStatus().name()))`; call `dao.update(existing.get())`; return `new ConversationEntryOutcome(existing.get(), false)`.
       - Otherwise (`requestedText` does not extend `existingText`): throw `new ConversationEntryConflictException("Incompatible retry: requested text does not extend the stored cumulative text")`.
     - The update branch never reassigns `sequenceNumber`.
  6. Implement `public List<ConversationEntry> replay(Chat chat)`: `return dao.findVisibleTerminalEntriesForChat(chat.getId());`.
- **Dependencies:** Task 2, Task 6, Task 1.

### 9. `src/main/java/org/tkit/onecx/chat/rs/internal/controllers/ConversationEntriesRestController.java`
- **Action:** create
- **Summary:** New JAX-RS controller implementing the generated `ConversationEntriesInternalApi` interface (produced by the `openapi-generator-maven-plugin` from Task 1's new tag `conversationEntriesInternal`), following `ChatsRestController.java`'s structure.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.rs.internal.controllers`, imports mirroring `ChatsRestController.java` plus `gen.org.tkit.onecx.chat.rs.internal.ConversationEntriesInternalApi`, `org.tkit.onecx.chat.domain.daos.ChatDAO`, `org.tkit.onecx.chat.rs.internal.services.ConversationEntryService`, `org.tkit.onecx.chat.rs.internal.mappers.ConversationEntryMapper`, `gen.org.tkit.onecx.chat.rs.internal.model.CreateOrUpdateConversationEntryDTO`.
  2. Declare `@Slf4j @ApplicationScoped @Transactional(value = NOT_SUPPORTED) public class ConversationEntriesRestController implements ConversationEntriesInternalApi` with `@Inject ChatDAO dao;`, `@Inject ConversationEntryService service;`, `@Inject ConversationEntryMapper mapper;`, `@Context UriInfo uriInfo;`.
  3. Implement `@Override public Response createOrUpdateConversationEntry(String chatId, CreateOrUpdateConversationEntryDTO createOrUpdateConversationEntryDTO)`: look up `var chat = dao.findById(chatId);` returning `Response.status(Response.Status.NOT_FOUND).build()` when `chat == null`; otherwise wrap `service.createOrUpdate(chat, createOrUpdateConversationEntryDTO)` in a try/catch on `ConversationEntryService.ConversationEntryConflictException`, mapping the outcome entry via `mapper.map(...)`, returning `Response.created(uriInfo.getAbsolutePathBuilder().path(outcome.getEntry().getId()).build()).entity(dto).build()` when `outcome.isCreated()` and `Response.ok(dto).build()` otherwise; the catch block returns `Response.status(Response.Status.CONFLICT).build()`.
  4. Implement `@Override public Response getConversationEntries(String chatId)`: look up `var chat = dao.findById(chatId);` returning `Response.status(Response.Status.NOT_FOUND).build()` when `chat == null`; otherwise `return Response.ok(mapper.mapList(service.replay(chat))).build();`.
- **Dependencies:** Task 1, Task 6, Task 7, Task 8.

### 10. `src/test/resources/data/testdata-internal.xml`
- **Action:** modify
- **Summary:** Add fixture `CONVERSATION_ENTRY` rows for both the default tenant and the `tenant-100`/`tenant-200` tenant-scoped chats, to back DAO/controller tests without requiring tests to create entries via `POST` first.
- **Concrete TODOs:**
  1. Add a `<!-- CONVERSATION_ENTRY -->` comment block after the existing default-tenant `<!-- MESSAGE -->` block, containing:
     `<CONVERSATION_ENTRY guid="entry-11-111" optlock="0" chat_id="chat-22-222" tenant_id="default" idempotency_key="idem-11-111" sequence_number="1" entry_type="HUMAN_UTTERANCE" text="Why is the sky blue?" status="COMPLETED" user_id="Human"/>` and
     `<CONVERSATION_ENTRY guid="entry-22-222" optlock="0" chat_id="chat-22-222" tenant_id="default" idempotency_key="idem-22-222" sequence_number="2" entry_type="ASSISTANT_CHECKPOINT" text="Rayleigh scattering." status="IN_PROGRESS" user_id="Bot"/>`.
  2. Add a second `<!-- CONVERSATION_ENTRY -->` comment block in the `<!-- TENANT -->` section, containing:
     `<CONVERSATION_ENTRY guid="t-entry-11-111" optlock="0" chat_id="t-chat-22-222" tenant_id="tenant-100" idempotency_key="t-idem-11-111" sequence_number="1" entry_type="HUMAN_UTTERANCE" text="Why is the sky blue?" status="COMPLETED" user_id="Human"/>` and
     `<CONVERSATION_ENTRY guid="t-entry-22-222" optlock="0" chat_id="t-chat-22-222" tenant_id="tenant-100" idempotency_key="t-idem-22-222" sequence_number="2" entry_type="TERMINAL_OUTCOME" text="" status="INTERRUPTED" user_id="Bot"/>`.
- **Dependencies:** Task 3.

### 11. `src/test/java/org/tkit/onecx/chat/domain/daos/ConversationEntryDAOTest.java`
- **Action:** create
- **Summary:** DAO exception-path test mirroring `MessageDAOTest.java` exactly (mock `EntityManager` to throw on `getCriteriaBuilder()`, assert each DAO method wraps the failure in `DAOException` with the correct `ErrorKeys`).
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.domain.daos`, imports mirroring `MessageDAOTest.java` (`jakarta.inject.Inject`, `jakarta.persistence.EntityManager`, `org.junit.jupiter.api.Assertions`, `org.junit.jupiter.api.BeforeEach`, `org.junit.jupiter.api.Test`, `org.junit.jupiter.api.function.Executable`, `org.mockito.Mockito`, `org.tkit.quarkus.jpa.exceptions.DAOException`, `io.quarkus.test.InjectMock`, `io.quarkus.test.junit.QuarkusTest`).
  2. Add `@QuarkusTest class ConversationEntryDAOTest` with `@Inject ConversationEntryDAO dao;` and `@InjectMock EntityManager em;`.
  3. Add `@BeforeEach void beforeAll() { Mockito.when(em.getCriteriaBuilder()).thenThrow(new RuntimeException("Test technical error exception")); }`.
  4. Add `@Test void methodExceptionTests()` calling `methodExceptionTests(() -> dao.findByChatIdAndIdempotencyKey("chatId", "key"), ConversationEntryDAO.ErrorKeys.ERROR_FIND_BY_CHAT_AND_IDEMPOTENCY_KEY);`, `methodExceptionTests(() -> dao.findMaxSequenceNumberForChat("chatId"), ConversationEntryDAO.ErrorKeys.ERROR_FIND_MAX_SEQUENCE_NUMBER);`, and `methodExceptionTests(() -> dao.findVisibleTerminalEntriesForChat("chatId"), ConversationEntryDAO.ErrorKeys.ERROR_FIND_VISIBLE_TERMINAL_ENTRIES);`.
  5. Add the helper `void methodExceptionTests(Executable fn, Enum<?> key) { var exc = Assertions.assertThrows(DAOException.class, fn); Assertions.assertEquals(key, exc.key); }` copied from `MessageDAOTest`.
- **Dependencies:** Task 6.

### 12. `src/test/java/org/tkit/onecx/chat/domain/daos/ConversationEntryDAOPersistenceTest.java`
- **Action:** create
- **Summary:** Real-database persistence test (no mocked `EntityManager`) verifying sequence lookup and terminal-only filtering directly against the DAO, using the `@WithDBData` fixtures added in Task 10.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.domain.daos`, `@QuarkusTest @WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)` class `ConversationEntryDAOPersistenceTest`, `@Inject ConversationEntryDAO dao;`.
  2. Add `@Test void findVisibleTerminalEntriesForChatExcludesInProgressAndOrdersBySequence()`: call `dao.findVisibleTerminalEntriesForChat("chat-22-222")`, assert the result has exactly one entry, assert its `id` equals `"entry-11-111"` and its `sequenceNumber` equals `1L`.
  3. Add `@Test void findMaxSequenceNumberForChatReturnsHighestSequence()`: call `dao.findMaxSequenceNumberForChat("chat-22-222")`, assert it equals `2L`.
  4. Add `@Test void findMaxSequenceNumberForChatReturnsZeroWhenNoEntries()`: call `dao.findMaxSequenceNumberForChat("chat-11-111")`, assert it equals `0L`.
  5. Add `@Test void findByChatIdAndIdempotencyKeyReturnsMatch()`: call `dao.findByChatIdAndIdempotencyKey("chat-22-222", "idem-11-111")`, assert the result `isPresent()` and its `id` equals `"entry-11-111"`.
- **Dependencies:** Task 6, Task 10.

### 13. `src/test/java/org/tkit/onecx/chat/rs/internal/services/ConversationEntryServiceTest.java`
- **Action:** create
- **Summary:** Service-level `@QuarkusTest` covering the full business-logic matrix: first-write sequence allocation, identical-retry no-op, monotonic-growth update, incompatible-retry conflict, terminal finalization, post-terminal immutability conflict, and replay filtering.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.rs.internal.services`, `@QuarkusTest @WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)` class `ConversationEntryServiceTest`, `@Inject ConversationEntryService service;`, `@Inject ChatDAO chatDao;`.
  2. Add `@Test void firstWriteAllocatesNextSequenceNumber()`: build a `CreateOrUpdateConversationEntryDTO` with `idempotencyKey="new-key-1"`, `entryType=ConversationEntryTypeDTO.HUMAN_UTTERANCE`, `text="Hello"`; call `service.createOrUpdate(chatDao.findById("chat-22-222"), dto)`; assert `outcome.isCreated()` is `true` and `outcome.getEntry().getSequenceNumber()` equals `3L`.
  3. Add `@Test void identicalRetryIsNoOp()`: call `service.createOrUpdate` twice with `idempotencyKey="idem-repeat-1"` and identical `text="Same text"`, `status=null` on both calls; assert the second call's `outcome.isCreated()` is `false` and `outcome.getEntry().getSequenceNumber()` equals the first call's sequence number.
  4. Add `@Test void monotonicGrowthUpdatesTextAndKeepsSequence()`: create with `idempotencyKey="idem-grow-1" text="Hel"`, capture `sequenceNumber`; call again with `text="Hello world"`; assert the returned entry's `text` equals `"Hello world"` and `sequenceNumber` equals the captured value.
  5. Add `@Test void incompatibleRetryThrowsConflict()`: create with `idempotencyKey="idem-conflict-1" text="Hello"`; assert `Assertions.assertThrows(ConversationEntryService.ConversationEntryConflictException.class, () -> service.createOrUpdate(chat, dtoWithText("idem-conflict-1", "Goodbye")))`.
  6. Add `@Test void terminalStatusIsImmutableAfterFinalization()`: create with `idempotencyKey="idem-terminal-1" text="Done" status=ConversationEntryStatusDTO.COMPLETED`; assert `Assertions.assertThrows(ConversationEntryService.ConversationEntryConflictException.class, () -> service.createOrUpdate(chat, dtoWithText("idem-terminal-1", "Done")))` on the second call.
  7. Add `@Test void replayReturnsOnlyTerminalInSequenceOrder()`: call `service.replay(chatDao.findById("chat-22-222"))`; assert the result contains exactly one entry with `id` equal to `"entry-11-111"`.
- **Dependencies:** Task 8, Task 10.

### 14. `src/test/java/org/tkit/onecx/chat/rs/internal/controllers/ConversationEntriesRestControllerTest.java`
- **Action:** create
- **Summary:** Full HTTP-level test mirroring `ChatsRestControllerTest.java`'s structure, covering create (`201`), idempotent update (`200`, no-op and monotonic growth), conflict (`409`), not-found (`404`), and replay ordering/filtering via the `GET` endpoint, within the default tenant.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.rs.internal.controllers`, `@QuarkusTest @TestHTTPEndpoint(ConversationEntriesRestController.class) @WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true) @GenerateKeycloakClient(clientName = "testClient", scopes = { "ocx-chat:all", "ocx-chat:conversation-entries:read", "ocx-chat:conversation-entries:write" })` class `ConversationEntriesRestControllerTest extends AbstractTest`.
  2. Add `@Test void createConversationEntryReturnsCreatedWithSequenceOne()`: build a `CreateOrUpdateConversationEntryDTO` (`idempotencyKey="http-idem-1"`, `entryType=ConversationEntryTypeDTO.HUMAN_UTTERANCE`, `text="Hi"`), POST it via `given().auth().oauth2(getKeycloakClientToken("testClient")).contentType(APPLICATION_JSON).header(APM_HEADER_PARAM, createToken("org1")).body(dto).post("chat-11-111/conversation-entries")`, assert `statusCode(CREATED.getStatusCode())`, extract as `ConversationEntryDTO`, assert `sequenceNumber` equals `1`.
  3. Add `@Test void repeatedIdenticalPostReturnsOkNoOp()`: POST the identical body twice with the same `idempotencyKey` to `chat-11-111/conversation-entries`; assert the first response status is `CREATED` and the second is `OK`, and both responses report the same `sequenceNumber`.
  4. Add `@Test void monotonicGrowthPostReturnsOkWithUpdatedText()`: POST with `text="Hel"`, then POST the same `idempotencyKey` with `text="Hello"` to `chat-11-111/conversation-entries`; assert the second response status is `OK` and `getText()` equals `"Hello"`.
  5. Add `@Test void incompatibleRetryPostReturnsConflict()`: POST with `text="Hello"`, then POST the same `idempotencyKey` with `text="Goodbye"` to `chat-11-111/conversation-entries`; assert the second response `statusCode(CONFLICT.getStatusCode())`.
  6. Add `@Test void postToUnknownChatReturnsNotFound()`: POST to `unknown-chat-id/conversation-entries`; assert `statusCode(NOT_FOUND.getStatusCode())`.
  7. Add `@Test void getConversationEntriesReturnsOnlyTerminalInSequenceOrder()`: GET `chat-22-222/conversation-entries` with header `APM_HEADER_PARAM, createToken("org1")`; assert `statusCode(OK.getStatusCode())`; extract as `List<ConversationEntryDTO>`; assert size `1` and the single item's `id` equals `"entry-11-111"`.
  8. Add `@Test void getConversationEntriesForUnknownChatReturnsNotFound()`: GET `unknown-chat-id/conversation-entries`; assert `statusCode(NOT_FOUND.getStatusCode())`.
- **Dependencies:** Task 9, Task 10.

### 15. `src/test/java/org/tkit/onecx/chat/rs/internal/controllers/ConversationEntriesRestControllerTenantTest.java`
- **Action:** create
- **Summary:** Tenant-isolation test mirroring `ChatsRestControllerTenantTest.java`, asserting cross-tenant access to conversation entries is rejected and same-tenant access succeeds with both terminal fixture rows visible.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.rs.internal.controllers`, `@QuarkusTest @TestHTTPEndpoint(ConversationEntriesRestController.class) @WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true) @GenerateKeycloakClient(clientName = "testClient", scopes = { "ocx-chat:all", "ocx-chat:conversation-entries:read", "ocx-chat:conversation-entries:write" })` class `ConversationEntriesRestControllerTenantTest extends AbstractTest`.
  2. Add `@Test void getConversationEntriesCrossTenantReturnsNotFound()`: GET `t-chat-22-222/conversation-entries` with header `APM_HEADER_PARAM, createToken("org2")` (the chat belongs to `tenant-100`, mapped to `org1`); assert `statusCode(NOT_FOUND.getStatusCode())`.
  3. Add `@Test void getConversationEntriesSameTenantReturnsBothTerminalEntriesInOrder()`: GET `t-chat-22-222/conversation-entries` with header `APM_HEADER_PARAM, createToken("org1")`; assert `statusCode(OK.getStatusCode())`; extract as `List<ConversationEntryDTO>`; assert size `2`; assert the first element's `id` equals `"t-entry-11-111"` (`sequenceNumber` `1`) and the second element's `id` equals `"t-entry-22-222"` (`sequenceNumber` `2`), because both fixture rows (`COMPLETED` and `INTERRUPTED`) are terminal and therefore visible.
  4. Add `@Test void createConversationEntryCrossTenantReturnsNotFound()`: build a valid `CreateOrUpdateConversationEntryDTO` (`idempotencyKey="cross-tenant-1"`, `entryType=ConversationEntryTypeDTO.HUMAN_UTTERANCE`, `text="Hi"`), POST it to `t-chat-22-222/conversation-entries` with header `APM_HEADER_PARAM, createToken("org2")`; assert `statusCode(NOT_FOUND.getStatusCode())`.
- **Dependencies:** Task 9, Task 10.

### 16. `src/test/java/org/tkit/onecx/chat/rs/internal/controllers/ConversationEntriesRestControllerTestIT.java`
- **Action:** create
- **Summary:** Integration-test wrapper mirroring `ChatsRestControllerTenantTestIT.java`'s pattern, so the tenant-isolation scenarios also run under `@QuarkusIntegrationTest`.
- **Concrete TODOs:**
  1. Create the file with package `org.tkit.onecx.chat.rs.internal.controllers`, content:
     ```java
     package org.tkit.onecx.chat.rs.internal.controllers;

     import io.quarkus.test.junit.QuarkusIntegrationTest;

     @QuarkusIntegrationTest
     class ConversationEntriesRestControllerTestIT extends ConversationEntriesRestControllerTenantTest {
     }
     ```
- **Dependencies:** Task 15.

### 17. `docs/modules/onecx-chat-svc/pages/onecx-chat-svc-conversation-entries.adoc`
- **Action:** create
- **Summary:** New Antora page documenting the Conversation Entry lifecycle and replay contract, satisfying the Definition of Done's documentation requirement.
- **Concrete TODOs:**
  1. Create the file starting with header `= Conversation Entries` followed by section `== Overview` containing one paragraph explaining that Conversation Entries are a sequence-ordered, idempotent-write persistence model living alongside (not replacing) the existing `Message` model, used by authenticated internal runtimes to durably record finalized human speech (`HUMAN_UTTERANCE`), cumulative assistant checkpoints (`ASSISTANT_CHECKPOINT`), and terminal outcomes (`TERMINAL_OUTCOME`).
  2. Add section `== Lifecycle` with a bullet list stating: creation happens via `POST /internal/chats/{chatId}/conversation-entries` with a required `idempotencyKey`; the first write allocates a stable `sequenceNumber` (`MAX(sequenceNumber) + 1` for the chat) that subsequent updates never change; entries start in `IN_PROGRESS` status; while `IN_PROGRESS`, repeated writes with identical `text` are no-ops (`200 OK`, unchanged state), writes whose `text` extends the stored text as a prefix update the row in place (cumulative/monotonic growth), and writes whose `text` does not extend the stored text are rejected with `409 Conflict`; setting `status` to `COMPLETED` or `INTERRUPTED` finalizes the entry exactly once; once terminal, the entry is immutable and any further write for that `idempotencyKey` returns `409 Conflict`.
  3. Add section `== Replay Contract` with a bullet list stating: `GET /internal/chats/{chatId}/conversation-entries` returns only entries with terminal status (`COMPLETED` or `INTERRUPTED`), ordered ascending by `sequenceNumber`; entries with `IN_PROGRESS` status are never returned; the endpoint returns `404` when the `chatId` does not resolve to a chat visible to the caller's tenant.
  4. Add section `== Authorization` with a bullet list stating: endpoints require OAuth2 scopes `ocx-chat:conversation-entries:write` (create/update) and `ocx-chat:conversation-entries:read` (replay), or the blanket `ocx-chat:all` scope; tenant isolation is enforced transparently via Hibernate discriminator-based multi-tenancy (`quarkus.hibernate-orm.multitenant=DISCRIMINATOR`), so a chat belonging to another tenant is treated as not found.
- **Dependencies:** Task 1, Task 8.

### 18. `docs/modules/onecx-chat-svc/pages/index.adoc`
- **Action:** modify
- **Summary:** Wire the new documentation page into the existing Antora index.
- **Concrete TODOs:**
  1. Insert the line `include::onecx-chat-svc-conversation-entries.adoc[opts=optional]` directly after the existing `include::onecx-chat-svc-docs.adoc[opts=optional]` line.
- **Dependencies:** Task 17.

## Verification Steps

Run these from the repository root (`/tmp/github-runner-onecx-2/onecx-ai-workflows/onecx-ai-workflows/hybrid-orchestrator-work/.tmp-hybrid/target-repo`) after all tasks are implemented:

1. **Compile and generate sources** (validates the OpenAPI addition and entity/DAO/mapper compile together):
   ```bash
   mvn -q -DskipTests clean compile
   ```
   Expected result: `BUILD SUCCESS`, and `target/generated-sources` contains `gen/org/tkit/onecx/chat/rs/internal/ConversationEntriesInternalApi.java` plus `gen/org/tkit/onecx/chat/rs/internal/model/ConversationEntryDTO.java` and `CreateOrUpdateConversationEntryDTO.java`.

2. **Run new DAO and service tests in isolation:**
   ```bash
   mvn -q -Dtest=ConversationEntryDAOTest,ConversationEntryDAOPersistenceTest,ConversationEntryServiceTest test
   ```
   Expected result: `Tests run: N, Failures: 0, Errors: 0` for all three classes.

3. **Run new controller tests in isolation:**
   ```bash
   mvn -q -Dtest=ConversationEntriesRestControllerTest,ConversationEntriesRestControllerTenantTest test
   ```
   Expected result: all tests pass, including the `409`/`404`/`201`/`200` status assertions.

4. **Run the full existing test suite** to confirm no regression to `Message`/`Chat`/`Participant` behavior:
   ```bash
   mvn -q test
   ```
   Expected result: `BUILD SUCCESS`, all pre-existing test classes (`ChatsRestControllerTest`, `ChatsRestControllerTenantTest`, `MessageDAOTest`, `ChatDAOTest`, `ParticipantDAOTest`, `ChatsServiceTest`, `AsyncAiProcessingServiceTest`) still pass unchanged.

5. **Liquibase validation:** confirm no `LiquibaseException` appears in the `mvn test` output from step 4, since every `@QuarkusTest` run applies `quarkus.liquibase.migrate-at-start=true` and `quarkus.liquibase.validate-on-migrate=true` against the changelog including the new changeset from Task 3.

6. **OpenAPI YAML syntax check:**
   ```bash
   python3 -c "import yaml; yaml.safe_load(open('src/main/openapi/onecx-chat-internal-openapi.yaml'))" && echo "YAML valid"
   ```
   Expected result: prints `YAML valid` with no exception raised.

## Notes

- The database-level unique constraint on `(chat_id, idempotency_key)` (Task 3) enforces identity-level idempotency at the storage layer; the text-prefix compatibility rule for cumulative checkpoint growth is enforced in `ConversationEntryService` application code (Task 8) because prefix compatibility is not expressible as a standard SQL uniqueness or check constraint.
- The database-level unique constraint on `(chat_id, idempotency_key)` also causes a second, concurrent first-write for the same new `idempotencyKey` to fail with a persistence constraint violation rather than create a duplicate row, giving referential correctness under concurrent access at the storage layer independent of the service-layer read-then-write logic.
- The new OAuth2 scopes `ocx-chat:conversation-entries:read` and `ocx-chat:conversation-entries:write` are separate from the existing `ocx-chat:read`/`write`/`delete` scopes, giving the internal runtime client a narrower, independently provisionable authorization surface than the general chat UI/API consumers that use the pre-existing scopes.
- `ChatDAO.findById` is reused for both the existence check and tenant scoping in the new controller; this method loads the `Chat.CHAT_LOAD` entity graph (eagerly fetching messages and participants), which is additional data beyond what the conversation-entry endpoints require, but produces correct tenant-scoped `404` behavior consistent with the rest of the controller.
