package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Path-style AWS integrations whose operation comes from the integration itself rather than a
 * rendered query-protocol body: an S3 object PUT at a path with a {@code {name}} placeholder, and
 * SQS calls whose {@code Action} and parameters are mapped {@code integration.request.querystring}
 * values, with the queue named by the path.
 */
@QuarkusTest
class ApiGatewayAwsPathIntegrationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ACCOUNT_ID = "000000000000";
    private static final String BUCKET = "apigw-s3-path-bucket";
    private static final String QUEUE_NAME = "apigw-sqs-path-agent-a1";
    private static final String STAGE = "test";

    private static String apiId;
    private static String queueUrl;

    @BeforeEach
    void setUp() throws Exception {
        if (apiId != null) {
            return;
        }
        given().when().put("/" + BUCKET).then().statusCode(200);
        queueUrl = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", QUEUE_NAME)
                .when().post("/")
                .then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"aws-path-integrations\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        String agentId = createResource(rootId, "{agentid}");
        String sysinfoId = createResource(agentId, "sysinfo");
        String diagnosticsId = createResource(agentId, "diagnostics");
        String messageId = createResource(agentId, "message");

        ObjectNode sysinfo = integration("PUT", "s3:path/" + BUCKET + "/sysinfo/{agentid}.json", "NEVER");
        sysinfo.set("requestParameters", mapper.valueToTree(Map.of(
                "integration.request.path.agentid", "method.request.path.agentid",
                "integration.request.header.x-amz-acl", "'private'")));
        sysinfo.set("requestTemplates", mapper.valueToTree(Map.of("application/json", "$input.body")));
        putMethod(sysinfoId, "POST", sysinfo);

        ObjectNode diagnostics = integration("PUT", "s3:path/" + BUCKET + "/diagnostics/{agentid}.zip",
                "WHEN_NO_TEMPLATES");
        diagnostics.set("requestParameters", mapper.valueToTree(Map.of(
                "integration.request.path.agentid", "method.request.path.agentid",
                "integration.request.header.Content-Type", "'application/zip'")));
        putMethod(diagnosticsId, "POST", diagnostics);

        ObjectNode receive = integration("GET", "sqs:path/" + ACCOUNT_ID + "/apigw-sqs-path-agent-{agentid}",
                "NEVER");
        receive.set("requestParameters", mapper.valueToTree(Map.of(
                "integration.request.querystring.Action", "'ReceiveMessage'",
                "integration.request.querystring.MaxNumberOfMessages", "'1'",
                "integration.request.querystring.MessageAttributeNames", "'DestinationId'",
                "integration.request.querystring.AttributeNames", "'SentTimestamp'",
                "integration.request.querystring.Version", "'2012-11-05'",
                "integration.request.path.agentid", "method.request.path.agentid")));
        receive.set("requestTemplates", mapper.valueToTree(Map.of("application/json", "")));
        putMethod(messageId, "GET", receive);

        ObjectNode delete = integration("GET", "sqs:path/" + ACCOUNT_ID + "/apigw-sqs-path-agent-{agentid}",
                "WHEN_NO_MATCH");
        delete.set("requestParameters", mapper.valueToTree(Map.of(
                "integration.request.querystring.Action", "'DeleteMessage'",
                "integration.request.querystring.ReceiptHandle", "method.request.querystring.receipt",
                "integration.request.querystring.Version", "'2012-11-05'",
                "integration.request.path.agentid", "method.request.path.agentid")));
        putMethod(messageId, "DELETE", delete);

        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");
        given().contentType(ContentType.JSON)
                .body(Map.of("stageName", STAGE, "deploymentId", deploymentId))
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    void s3PathIntegration_putsTheRenderedBodyAtTheSubstitutedKey() {
        given().contentType(ContentType.JSON)
                .body("{\"cpu\":\"x64\"}")
                .when().post(execute("a1/sysinfo"))
                .then().statusCode(200);

        given().when().get("/" + BUCKET + "/sysinfo/a1.json")
                .then().statusCode(200)
                .contentType(containsString("application/json"))
                .body(equalTo("{\"cpu\":\"x64\"}"));
    }

    @Test
    void s3PathIntegration_passesABinaryBodyThroughUnchanged() {
        byte[] zip = {0x50, 0x4b, 0x03, 0x04, (byte) 0xff, 0x00, (byte) 0x80, 0x7f};
        given().contentType("application/zip")
                .body(zip)
                .when().post(execute("a1/diagnostics"))
                .then().statusCode(200);

        byte[] stored = given().when().get("/" + BUCKET + "/diagnostics/a1.zip")
                .then().statusCode(200)
                .contentType(containsString("application/zip"))
                .extract().asByteArray();
        assertArrayEquals(zip, stored);
    }

    @Test
    void sqsPathIntegration_receivesAndDeletesWithMappedQueryParameters() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MessageBody", "aGVsbG8=")
                .formParam("MessageAttribute.1.Name", "DestinationId")
                .formParam("MessageAttribute.1.Value.DataType", "String")
                .formParam("MessageAttribute.1.Value.StringValue", "dest-1")
                .when().post("/")
                .then().statusCode(200);

        Response received = given().contentType(ContentType.JSON)
                .when().get(execute("a1/message"))
                .then().statusCode(200)
                .contentType(containsString("application/json"))
                .body("ReceiveMessageResponse.ReceiveMessageResult.messages[0].Body", equalTo("aGVsbG8="))
                .body("ReceiveMessageResponse.ReceiveMessageResult.messages[0].MessageAttributes[0].Name",
                        equalTo("DestinationId"))
                .body("ReceiveMessageResponse.ReceiveMessageResult.messages[0].MessageAttributes[0].Value.StringValue",
                        equalTo("dest-1"))
                .body("ReceiveMessageResponse.ReceiveMessageResult.messages[0].MessageAttributes[0].Value",
                        hasKey("BinaryListValues"))
                .body("ReceiveMessageResponse.ReceiveMessageResult.messages[0].MessageAttributes[0].Value",
                        hasKey("StringListValues"))
                .body("ReceiveMessageResponse.ReceiveMessageResult.messages[0].Attributes[0].Name",
                        equalTo("SentTimestamp"))
                .extract().response();
        String receipt = received.path("ReceiveMessageResponse.ReceiveMessageResult.messages[0].ReceiptHandle");

        given().queryParam("receipt", receipt)
                .when().delete(execute("a1/message"))
                .then().statusCode(200)
                .body("DeleteMessageResponse.ResponseMetadata.RequestId", notNullValue());

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "All")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Name>ApproximateNumberOfMessages</Name><Value>0</Value>"))
                .body(containsString("<Name>ApproximateNumberOfMessagesNotVisible</Name><Value>0</Value>"));
        given().contentType(ContentType.JSON)
                .when().get(execute("a1/message"))
                .then().statusCode(200)
                .body("ReceiveMessageResponse.ReceiveMessageResult.messages", nullValue());
    }

    @Test
    void sqsPathIntegration_answersAJsonErrorForAMissingQueue() {
        given().contentType(ContentType.JSON)
                .when().get(execute("missing/message"))
                .then()
                .contentType(containsString("application/json"))
                .body("Error.Code", containsString("NonExistentQueue"));
    }

    private static String execute(String path) {
        return "/execute-api/" + apiId + "/" + STAGE + "/" + path;
    }

    private static String createResource(String parentId, String pathPart) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("pathPart", pathPart))
                .when().post("/restapis/" + apiId + "/resources/" + parentId)
                .then().statusCode(201)
                .extract().path("id");
    }

    private static ObjectNode integration(String httpMethod, String uriSuffix, String passthroughBehavior) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "AWS");
        node.put("httpMethod", httpMethod);
        node.put("uri", "arn:aws:apigateway:us-east-1:" + uriSuffix);
        node.put("passthroughBehavior", passthroughBehavior);
        return node;
    }

    private static void putMethod(String resourceId, String httpMethod, ObjectNode integration) throws Exception {
        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod;
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put(methodPath)
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integration))
                .when().put(methodPath + "/integration")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\"}")
                .when().put(methodPath + "/integration/responses/200")
                .then().statusCode(201);
    }
}
