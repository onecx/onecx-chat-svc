package org.tkit.onecx.chat.rs.internal.controllers;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.util.List;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.HttpHeaders;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.Header;
import org.tkit.onecx.chat.domain.criteria.ChatMessageSearchCriteria;
import org.tkit.onecx.chat.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;
import org.tkit.quarkus.test.WithDBData;

import gen.org.tkit.onecx.chat.rs.internal.model.*;
import io.quarkiverse.mockserver.test.InjectMockServerClient;
import io.quarkiverse.mockserver.test.MockServerTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;

@QuarkusTest
@TestHTTPEndpoint(ChatsRestController.class)
@QuarkusTestResource(MockServerTestResource.class)
@WithDBData(value = "data/testdata-internal.xml", deleteBeforeInsert = true, deleteAfterTest = true, rinseAndRepeat = true)
@GenerateKeycloakClient(clientName = "testClient", scopes = { "ocx-chat:all", "ocx-chat:read", "ocx-chat:write" })
class ChatsRestControllerTest extends AbstractTest {

    @InjectMockServerClient
    public MockServerClient mockServerClient;

    static final String MOCK_ID = "MOCK";

    @BeforeEach
    void resetExpectation() {
        try {
            mockServerClient.clear(MOCK_ID);
        } catch (Exception ex) {
            //  mockId not existing
        }
    }

    @Test
    void deleteChatTest() {

        // delete chat
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("id", "chat-DELETE_1")
                .delete("{id}")
                .then().statusCode(NO_CONTENT.getStatusCode());

        // check if chat exists
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("id", "chat-DELETE_1")
                .get("{id}")
                .then().statusCode(NOT_FOUND.getStatusCode());

        // delete chat in portal
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("id", "chat-11-111")
                .delete("{id}")
                .then()
                .statusCode(NO_CONTENT.getStatusCode());

    }

    @Test
    void getChatByIdTest() {

        var dto = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("id", "chat-22-222")
                .get("{id}")
                .then().statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .body().as(ChatDTO.class);

        assertThat(dto).isNotNull();
        assertThat(dto.getType()).isEqualTo(ChatTypeDTO.AI_CHAT);
        assertThat(dto.getId()).isEqualTo("chat-22-222");

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("id", "___")
                .get("{id}")
                .then().statusCode(NOT_FOUND.getStatusCode());

        dto = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("id", "chat-11-111")
                .get("{id}")
                .then().statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .body().as(ChatDTO.class);

        assertThat(dto).isNotNull();
        assertThat(dto.getType()).isEqualTo(ChatTypeDTO.HUMAN_DIRECT_CHAT);
        assertThat(dto.getId()).isEqualTo("chat-11-111");
        assertThat(dto.getParticipants()).isNotNull();
        assertThat(dto.getParticipants()).isNotNull().hasSize(1);

    }

    @Test
    void getChatsTest() {
        var data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .get()
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(4);
        assertThat(data.getStream()).isNotNull().hasSize(4);

    }

    @Test
    void searchChatsTest() {
        var criteria = new ChatSearchCriteriaDTO();

        var data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/search")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(4);
        assertThat(data.getStream()).isNotNull().hasSize(4);

        criteria.setType(null);
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/search")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(4);
        assertThat(data.getStream()).isNotNull().hasSize(4);

        criteria.setType(ChatTypeDTO.HUMAN_DIRECT_CHAT);
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/search")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(1);
        assertThat(data.getStream()).isNotNull().hasSize(1);

        criteria.setType(null);
        criteria.setParticipant(null);
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/search")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(4);
        assertThat(data.getStream()).isNotNull().hasSize(4);

        criteria.setType(null);
        criteria.setParticipant("user1");
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/search")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(1);
        assertThat(data.getStream()).isNotNull().hasSize(1);

        criteria.setTopic("topic chat-33-333");
        criteria.setParticipant(null);
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/search")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(1);
        assertThat(data.getStream()).isNotNull().hasSize(1);

        criteria.setTopic(" ");
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/search")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatPageResultDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getTotalElements()).isEqualTo(4);
        assertThat(data.getStream()).isNotNull().hasSize(4);
    }

    @Test
    void getChatMessagesTest() {

        String chatId = "chat-22-222";

        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", chatId)
                .get("{chatId}/messages")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract().as(List.class);

        assertThat(response).isNotNull();
        assertThat(response).isNotNull().isNotEmpty().hasSize(3);

    }

    @Test
    void getChatMessagesNotFoundTest() {
        //when chat is null
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", "id-not-exist")
                .get("{chatId}/messages")
                .then()
                .statusCode(NOT_FOUND.getStatusCode())
                .extract();

        //when chat exist and list of messages is empty
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", "chat-33-333")
                .get("{chatId}/messages")
                .then()
                .statusCode(NOT_FOUND.getStatusCode())
                .extract();
    }

    @Test
    void createChatWithoutRequiredFieldsTest() {
        // create chat without required
        var chatDto = new CreateChatDTO();
        chatDto.setAppId("appId");
        chatDto.setTopic("topic");
        chatDto.setSummary("summary");

        var exception = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .post()
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())

                .extract().as(ProblemDetailResponseDTO.class);

        Assertions.assertNotNull(exception);
        Assertions.assertEquals("CONSTRAINT_VIOLATIONS", exception.getErrorCode());
        Assertions.assertEquals("createChat.createChatDTO.type: must not be null", exception.getDetail());

    }

    @Test
    void createHumanChatTest() {
        var chatDto = new CreateChatDTO();
        chatDto.setAppId("appId");
        chatDto.setType(ChatTypeDTO.HUMAN_DIRECT_CHAT);

        ParticipantDTO participantDto = new ParticipantDTO();
        participantDto.setEmail("example@email.com");
        participantDto.setUserId("jdoe");
        participantDto.setUserName("John Doe");
        participantDto.setType(ParticipantTypeDTO.HUMAN);
        chatDto.addParticipantsItem(participantDto);

        //create human chat
        var chat = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .post()
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .body().as(ChatDTO.class);

        Assertions.assertNotNull(chat);
        assertThat(chat).isNotNull();

        var chatResponseDto = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("id", chat.getId())
                .get("{id}")
                .then().statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .body().as(ChatDTO.class);

        assertThat(chatResponseDto).isNotNull();
        assertThat(chatResponseDto.getParticipants()).isNotNull().isNotEmpty().hasSize(1);

        // create message
        var messageDto = new CreateMessageDTO();
        messageDto.setType(MessageTypeDTO.HUMAN);
        messageDto.setText("This is a human question");
        messageDto.setUserId("humanId");

        var messageId = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .pathParam("chatId", chat.getId())
                .when()
                .contentType(APPLICATION_JSON)
                .body(messageDto)
                .post("{chatId}/messages")
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract();

        assertThat(messageId).isNotNull();

        //load messages
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", chat.getId())
                .get("{chatId}/messages")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract().as(new TypeRef<List<MessageDTO>>() {
                });

        assertThat(response).isNotNull();
        assertThat(response).isNotNull().isNotEmpty().hasSize(1);
        assertThat(response.get(0).getType()).isEqualTo(MessageTypeDTO.HUMAN);

    }

    @Test
    void addParticipantTest() {

        var chatDto = new CreateChatDTO();
        chatDto.setAppId("appId");
        chatDto.setType(ChatTypeDTO.AI_CHAT);

        //create ai chat
        var chat = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .post()
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .body().as(ChatDTO.class);

        Assertions.assertNotNull(chat);

        // add participant
        var addParticipantDto = new AddParticipantDTO();
        addParticipantDto.setUserId("ne.mail");
        addParticipantDto.setEmail("name@email.com");
        addParticipantDto.setUserName("user");
        addParticipantDto.setType(ParticipantTypeDTO.HUMAN);

        var messageId = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .pathParam("chatId", chat.getId())
                .when()
                .contentType(APPLICATION_JSON)
                .body(addParticipantDto)
                .post("{chatId}/participants")
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract();

        assertThat(messageId).isNotNull();

        //load participants
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", chat.getId())
                .get("{chatId}/participants")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract().as(new TypeRef<List<ParticipantDTO>>() {
                });

        assertThat(response).isNotNull();
        assertThat(response).isNotNull().isNotEmpty().hasSize(1);
        assertThat(response.get(0).getType()).isEqualTo(ParticipantTypeDTO.HUMAN);
    }

    @Test
    void addParticipantChatNotExistTest() {
        //when chat not exist
        var addParticipantDto = new AddParticipantDTO();
        addParticipantDto.setUserId("ne.mail");
        addParticipantDto.setEmail("name@email.com");
        addParticipantDto.setUserName("user");
        addParticipantDto.setType(ParticipantTypeDTO.HUMAN);

        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .pathParam("chatId", "id-not-exist")
                .when()
                .contentType(APPLICATION_JSON)
                .body(addParticipantDto)
                .post("{chatId}/participants")
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        assertThat(response).isNotNull();

    }

    @Test
    void getParticipantNotFoundTest() {
        //when chat not exist
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", "id-not-exist")
                .get("{chatId}/participants")
                .then()
                .statusCode(NOT_FOUND.getStatusCode())
                .extract();

        //when chat exist and list of participants is empty
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", "chat-33-333")
                .get("{chatId}/participants")
                .then()
                .statusCode(NOT_FOUND.getStatusCode())
                .extract();
    }

    @Test
    void createAiChatTest() {

        String responseFromMock = "{\r\n" +
                " \"conversationId\": \"123456\",\r\n" +
                "\"message\": \"Generated AI Answer text\",\r\n" +
                "\"type\": \"ASSISTANT\",\r\n" +
                "\"creationDate\": 1643684377000\r\n" +
                "}";

        mockServerClient.when(request()
                .withPath("/v1/dispatch/chat")
                .withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(200)
                        .withHeaders(new Header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON))
                        .withBody(responseFromMock));

        var chatDto = new CreateChatDTO();
        chatDto.setAppId("appId");
        chatDto.setType(ChatTypeDTO.AI_CHAT);

        //create ai chat
        var chat = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .post()
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .body().as(ChatDTO.class);

        Assertions.assertNotNull(chat);

        // create message
        var messageDto = new CreateMessageDTO();
        messageDto.setType(MessageTypeDTO.HUMAN);
        messageDto.setText("This is a human question");
        messageDto.setUserId("humanId");

        var messageId = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .pathParam("chatId", chat.getId())
                .when()
                .contentType(APPLICATION_JSON)
                .body(messageDto)
                .post("{chatId}/messages")
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract();

        assertThat(messageId).isNotNull();

        //load messages
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", chat.getId())
                .get("{chatId}/messages")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract().as(new TypeRef<List<MessageDTO>>() {
                });

        assertThat(response).isNotNull();
        assertThat(response).isNotNull().isNotEmpty().hasSize(2);
        assertThat(response.get(0).getType()).isEqualTo(MessageTypeDTO.HUMAN);
        assertThat(response.get(1).getType()).isEqualTo(MessageTypeDTO.ASSISTANT);
    }

    @Test
    void createChatMessageTest() {

        String responseFromMock = "{\r\n" +
                " \"conversationId\": \"123456\",\r\n" +
                "\"message\": \"Generated AI Answer text\",\r\n" +
                "\"type\": \"ASSISTANT\",\r\n" +
                "\"creationDate\": 1643684377000\r\n" +
                "}";

        mockServerClient.when(request()
                .withPath("/ai/chat")
                .withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(200)
                        .withHeaders(new Header(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON))
                        .withBody(responseFromMock));

        // create chat
        var chatDto = new CreateChatDTO();
        chatDto.setType(ChatTypeDTO.HUMAN_DIRECT_CHAT);
        chatDto.setAppId("appId");
        chatDto.setTopic("topic");
        chatDto.setSummary("summary");

        var chat = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .post()
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract()
                .body().as(ChatDTO.class);

        var chatId = chat.getId();

        // create message
        var messageDto = new CreateMessageDTO();
        messageDto.setType(MessageTypeDTO.HUMAN);
        messageDto.setText(
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nulla facilisi. Nunc varius tortor non diam volutpat, sit amet luctus felis pharetra. Integer nec erat vel elit posuere fermentum. Vivamus ac consectetur libero. Sed varius, ligula non lacinia fringilla, elit felis malesuada lacus, et eleifend odio quam eget urna. Duis vel elit ut quam laoreet imperdiet. Aliquam quis risus vitae libero fermentum luctus. Etiam condimentum ex nec nunc hendrerit, nec euismod dui consectetur. Maecenas hendrerit pharetra odio, ac vulputate arcu. Curabitur tristique erat nec venenatis ullamcorper. Duis accumsan, augue vel cursus scelerisque, elit justo blandit ligula, eget congue purus mauris eget dui. Nullam nec ante mauris.\r\n"
                        + //
                        "\r\n" + //
                        "Vestibulum ante ipsum primis in faucibus orci luctus et ultrices posuere cubilia Curae; Pellentesque facilisis turpis non odio efficitur laoreet. Vestibulum eleifend varius velit, nec feugiat justo tincidunt ac. Nam eget tortor quis lacus vulputate interdum in a quam. Ut ut dui eget tortor posuere ullamcorper. Integer interdum, sapien at gravida dictum, nisl justo laoreet ligula, sit amet tincidunt libero libero vitae elit. Vivamus ac aliquet arcu. Integer efficitur malesuada est, in volutpat libero feugiat sit amet. Quisque sed ex at quam dictum venenatis. Nunc bibendum luctus tortor, nec varius purus auctor nec. Sed non malesuada ligula. Duis eget nisl nec libero tristique fermentum a eu mauris.\r\n"
                        + //
                        "\r\n" + //
                        "Pellentesque habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. Sed fermentum libero ac lectus dapibus, ut scelerisque neque laoreet. Nam at venenatis risus. Nunc eleifend urna a sapien hendrerit, in auctor nunc lacinia. Aliquam sit amet massa vel elit sollicitudin interdum. Nullam laoreet, urna ut finibus bibendum, velit arcu gravida arcu, nec suscipit leo mi nec neque. Curabitur vel elit eu leo bibendum varius a sit amet odio. Integer et tristique libero. Vivamus cursus sagittis dapibus. Suspendisse potenti. Nullam euismod, lectus in tempus feugiat, arcu lacus commodo augue, vel malesuada elit leo a velit.\r\n"
                        + //
                        "\r\n" + //
                        "Donec imperdiet, nunc vel sodales rhoncus, odio lacus ultricies dolor, a bibendum nisi urna vel orci. Sed auctor felis vel dolor feugiat, in volutpat metus dapibus. Suspendisse et arcu ut metus fermentum congue a a mi. Integer pellentesque, ligula in tincidunt commodo, dui quam vulputate nisl, vitae luctus felis eros vel elit. Ut auctor urna ut lectus efficitur interdum. In hac habitasse platea dictumst. Maecenas eu massa quis odio blandit cursus vel at neque. Sed ut pharetra turpis. Duis auctor elit ac aliquet venenatis. Proin ut nisi vel ex laoreet vestibulum id eu libero. Vivamus eleifend, quam sit amet consectetur dignissim, enim velit blandit orci, at feugiat nunc justo vel justo. Proin vel dui a ex vulputate vestibulum.");
        messageDto.setUserId("GenAis");

        //create message
        var messageId = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .pathParam("chatId", chatId)
                .when()
                .contentType(APPLICATION_JSON)
                .body(messageDto)
                .post("{chatId}/messages")
                .then()
                .statusCode(CREATED.getStatusCode())
                .extract();

        assertThat(messageId).isNotNull();

        //load messages
        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .pathParam("chatId", chatId)
                .get("{chatId}/messages")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract().as(new TypeRef<List<MessageDTO>>() {
                });

        assertThat(response).isNotNull();
        assertThat(response).isNotNull().isNotEmpty().hasSize(1);
        assertThat(response.get(0).getUserId()).isEqualTo("GenAis");

    }

    @Test
    void createChatMessageChatNotExistTest() {
        CreateMessageDTO createMessageDTO = new CreateMessageDTO();
        createMessageDTO.setType(MessageTypeDTO.ASSISTANT);

        var response = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(createMessageDTO)
                .pathParam("chatId", "not-exist-chat-id")
                .post("{chatId}/messages")
                .then()
                .statusCode(NOT_FOUND.getStatusCode());

        assertThat(response).isNotNull();

    }

    @Test
    void createChatMessageExceptionFromAIServiceTest() {
        CreateMessageDTO createMessageDTO = new CreateMessageDTO();
        createMessageDTO.setType(MessageTypeDTO.ASSISTANT);
        createMessageDTO.setUserId("userId");
        createMessageDTO.setText("custom text");

        mockServerClient.when(request()
                .withPath("/ai/chat")
                .withMethod(HttpMethod.POST))
                .withId(MOCK_ID)
                .respond(httpRequest -> response().withStatusCode(500));

        var exception = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .when()
                .contentType(APPLICATION_JSON)
                .body(createMessageDTO)
                .pathParam("chatId", "chat-33-333")
                .post("{chatId}/messages")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .extract()
                .body().as(ProblemDetailResponseDTO.class);

        Assertions.assertNotNull(exception);
        Assertions.assertEquals("ERROR_CALLING_AI_CHAT_SERVICE", exception.getErrorCode());

    }

    @Test
    void updateChatTest() {

        // update none existing chat
        var chatDto = new UpdateChatDTO();
        chatDto.setType(ChatTypeDTO.HUMAN_DIRECT_CHAT);
        chatDto.setTopic("topic-update");

        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .when()
                .pathParam("id", "does-not-exists")
                .put("{id}")
                .then().statusCode(NOT_FOUND.getStatusCode());

        // update chat
        given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .when()
                .pathParam("id", "chat-11-111")
                .put("{id}")
                .then().statusCode(NO_CONTENT.getStatusCode());

        // download chat
        var dto = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(chatDto)
                .when()
                .pathParam("id", "chat-11-111")
                .get("{id}")
                .then().statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .body().as(ChatDTO.class);

        assertThat(dto).isNotNull();
        assertThat(dto.getTopic()).isEqualTo(chatDto.getTopic());

    }

    @Test
    void updateChatWithoutBodyTest() {

        var exception = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .when()
                .pathParam("id", "update_create_new")
                .put("{id}")
                .then()
                .statusCode(BAD_REQUEST.getStatusCode())
                .extract().as(ProblemDetailResponseDTO.class);

        Assertions.assertNotNull(exception);
        Assertions.assertEquals("CONSTRAINT_VIOLATIONS", exception.getErrorCode());
        Assertions.assertEquals("updateChat.updateChatDTO: must not be null",
                exception.getDetail());
        Assertions.assertNotNull(exception.getInvalidParams());
        Assertions.assertEquals(1, exception.getInvalidParams().size());
    }

    @Test
    void searchChatMessagesTest() {
        var criteria = new ChatMessageSearchCriteria();

        // CASE 1 — chatId = null → without participants and messages
        criteria.setChatId(null);
        var data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/searchChatMessages")
                .then()
                .statusCode(OK.getStatusCode())
                .contentType(APPLICATION_JSON)
                .extract()
                .as(ChatMessageResponseDTO.class);

        assertThat(data).isNotNull();
        assertThat(data.getMessages().getTotalElements()).isEqualTo(0);
        assertThat(data.getMessages().getStream()).isEmpty();
        assertThat(data.getParticipants()).isNotNull().hasSize(0);

        // CASE 2 — chatId = "chat-22-222"
        criteria.setChatId("chat-22-222");
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/searchChatMessages")
                .then()
                .statusCode(OK.getStatusCode())
                .extract()
                .as(ChatMessageResponseDTO.class);

        assertThat(data.getMessages().getTotalElements()).isEqualTo(3);
        assertThat(data.getMessages().getStream()).hasSize(3);
        assertThat(data.getParticipants()).hasSize(1);

        // CASE 3 — in criteria pageSize = 2
        criteria.setPageSize(2);
        data = given()
                .auth().oauth2(getKeycloakClientToken("testClient"))
                .contentType(APPLICATION_JSON)
                .body(criteria)
                .post("/searchChatMessages")
                .then()
                .extract()
                .as(ChatMessageResponseDTO.class);

        assertThat(data.getMessages().getTotalElements()).isEqualTo(3);
        assertThat(data.getMessages().getStream()).hasSize(2);
        assertThat(data.getParticipants()).hasSize(1);
    }
}
