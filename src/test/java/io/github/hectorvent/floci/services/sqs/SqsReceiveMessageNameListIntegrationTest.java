package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * ReceiveMessage reads its name lists under the singular or the plural name, bare or indexed, as
 * SQS's query protocol does. A bare value is one name, never split on commas.
 */
@QuarkusTest
class SqsReceiveMessageNameListIntegrationTest {

    private static String queueUrl;

    @BeforeEach
    void setUp() {
        if (queueUrl != null) {
            return;
        }
        queueUrl = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", "sqs-receive-name-lists")
                .when().post("/")
                .then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MessageBody", "name-lists")
                .formParam("MessageAttribute.1.Name", "DestinationId")
                .formParam("MessageAttribute.1.Value.DataType", "String")
                .formParam("MessageAttribute.1.Value.StringValue", "dest-1")
                .formParam("MessageAttribute.2.Name", "Other")
                .formParam("MessageAttribute.2.Value.DataType", "String")
                .formParam("MessageAttribute.2.Value.StringValue", "other")
                .when().post("/")
                .then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MessageAttributeName", "MessageAttributeName.1", "MessageAttributeNames",
            "MessageAttributeNames.1"})
    void messageAttributeNamesAreReadUnderEverySpelling(String parameter) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("VisibilityTimeout", "0")
                .formParam(parameter, "DestinationId")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Name>DestinationId</Name>"))
                .body(not(containsString("<Name>Other</Name>")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AttributeName", "AttributeName.1", "AttributeNames", "AttributeNames.1"})
    void systemAttributeNamesAreReadUnderEverySpelling(String parameter) {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("VisibilityTimeout", "0")
                .formParam(parameter, "SentTimestamp")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Name>SentTimestamp</Name>"));
    }

    @Test
    void aCommaSeparatedValueIsOneName() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("VisibilityTimeout", "0")
                .formParam("MessageAttributeNames", "DestinationId,Other")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Body>name-lists</Body>"))
                .body(not(containsString("<MessageAttribute>")));
    }
}
