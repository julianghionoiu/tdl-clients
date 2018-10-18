package tdl.client.queue;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.BatchResultErrorEntry;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequest;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.DeleteMessageBatchResult;
import com.amazonaws.services.sqs.model.GetQueueAttributesResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.PurgeQueueRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageBatchRequest;
import com.amazonaws.services.sqs.model.SendMessageBatchRequestEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tdl.client.audit.AuditStream;
import tdl.client.audit.Auditable;
import tdl.client.queue.abstractions.Request;
import tdl.client.queue.abstractions.UserImplementation;
import tdl.client.queue.abstractions.response.FatalErrorResponse;
import tdl.client.queue.abstractions.response.Response;
import tdl.client.queue.serialization.DeserializationException;
import tdl.client.queue.serialization.JsonRpcRequest;
import tdl.client.queue.serialization.JsonRpcResponse;
import tdl.client.queue.transport.BrokerCommunicationException;
import tdl.client.runner.connector.EventSendingFailureException;
import tdl.client.runner.connector.QueueSize;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class QueueBasedImplementationRunner implements ImplementationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueBasedImplementationRunner.class);
    private final Audit audit;
    private final ProcessingRules deployProcessingRules;
    private final ImplementationRunnerConfig config;

    private static final int MAX_NUMBER_OF_MESSAGES = 1;
    private static final int MAX_AWS_WAIT = 1;

    private static final String ATTRIBUTE_EVENT_NAME = "name";
    private static final String ATTRIBUTE_EVENT_VERSION = "version";

    private final ObjectMapper mapper;

    private AmazonSQS client;
    private String serviceEndpoint;

    private ReceiveMessageRequest receiveRequestQueueMessage;

    private CreateQueueRequest messageRequestQueue;
    private String messageRequestQueueUrl;
    private CreateQueueRequest messageResponse;
    private String messageResponseQueueUrl;
    private final Gson gson;

    private DeleteMessageBatchRequest batchOfMessagesToDeleteRequest;
    private List<DeleteMessageBatchRequestEntry> deleteMessageBatchRequestEntries;

    private SendMessageBatchRequest batchOfResponsesSendRequest;
    private List<SendMessageBatchRequestEntry> sendMessageBatchRequestEntries;

    private QueueBasedImplementationRunner(ImplementationRunnerConfig config, ProcessingRules deployProcessingRules) {
        logToConsole("        QueueBasedImplementationRunner creation [start]");
        this.config = config;
        this.deployProcessingRules = deployProcessingRules;
        audit = new Audit(config.getAuditStream());

        logToConsole("     QueueBasedImplementationRunner creation [start]");

        String hostname = config.getHostname();
        int port = config.getPort();

        Config customConfig = ConfigFactory.load();
        customConfig.checkValid(ConfigFactory.defaultReference());
        serviceEndpoint = String.format("http://%s:%d", hostname, port);
        client = createAWSClient(
                serviceEndpoint,
                customConfig.getString("sqs.signingRegion"),
                customConfig.getString("sqs.accessKey"),
                customConfig.getString("sqs.secretKey")
        );

        try {
            messageRequestQueue = createQueueRequest(customConfig.getString("sqs.requestQueueName"));
            messageRequestQueueUrl = createQueue(messageRequestQueue);
            messageResponse = createQueueRequest(customConfig.getString("sqs.responseQueueName"));
            messageResponseQueueUrl = createQueue(messageResponse);
        } catch (Exception ex) {
            logToConsole("        QueueBasedImplementationRunner [error]");
            String message = "There was a problem creating the queue runner";
            LOGGER.error(message, ex);
            audit.logException(message, ex);
        }

        receiveRequestQueueMessage = createReceiveRequest(messageRequestQueueUrl);

        mapper = new ObjectMapper();

        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Double.class,
                (JsonSerializer<Double>) (src, typeOfSrc, context) -> {
                    Integer value = (int) Math.round(src);
                    return new JsonPrimitive(value);
                });

        gson = gsonBuilder.serializeNulls().create();

        batchOfMessagesToDeleteRequest = new DeleteMessageBatchRequest().withQueueUrl(messageResponseQueueUrl);
        deleteMessageBatchRequestEntries = batchOfMessagesToDeleteRequest.getEntries();

        batchOfResponsesSendRequest = new SendMessageBatchRequest().withQueueUrl(messageResponseQueueUrl);
        sendMessageBatchRequestEntries = batchOfResponsesSendRequest.getEntries();

        logToConsole("        QueueBasedImplementationRunner creation [end]");
    }

    private ReceiveMessageRequest createReceiveRequest(String queueUrl) {
        return new ReceiveMessageRequest()
                .withMaxNumberOfMessages(MAX_NUMBER_OF_MESSAGES)
                .withQueueUrl(queueUrl)
                .withWaitTimeSeconds(MAX_AWS_WAIT)
                .withMessageAttributeNames(Arrays.asList(ATTRIBUTE_EVENT_NAME, ATTRIBUTE_EVENT_VERSION));
    }

    public List<String> getResponseQueueMessages() throws IOException {
        List<String> results = new ArrayList<>();

        ReceiveMessageRequest receiveMessageRequest = createReceiveRequest(messageResponseQueueUrl);
        ReceiveMessageResult batch;
        final List<Message> messages = new ArrayList<>();
        do {
            batch = client.receiveMessage(receiveMessageRequest);
            messages.addAll(batch.getMessages());
        } while (batch.getMessages().size() > 0);

        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            JsonNode jsonNode = mapper.readValue(message.getBody(), JsonNode.class);
            String payload = jsonNode.toString();
            JsonRpcResponse jsonRpcResponse = gson.fromJson(payload, JsonRpcResponse.class);
            Response response = jsonRpcResponse.toResponse();

            //TODO: implementation needs replacing, for some reason GSon is converting all int values into double
            String result;
            if ((response.getResult() == null) ||
                    isNumeric(response.getResult())) {

                Object resultAsInt = integerValueOf(response.getResult());
                result = String.format(
                        "{\"result\":%s,\"error\":null,\"id\":\"%s\"}",
                        resultAsInt, response.getId());
            } else {
                result = String.format(
                        "{\"result\":\"%s\",\"error\":null,\"id\":\"%s\"}",
                        response.getResult(), response.getId());
            }

            results.add(result);
        }

        return results;
    }

    private Object integerValueOf(Object value) {
        if (value instanceof Double) {
            return ((Double) value).intValue();
        }
        return value;
    }

    public void purgeRequestQueue() {
        purgeQueue(messageRequestQueue);
    }

    public void purgeResponseQueue() {
        purgeQueue(messageResponse);
    }

    public void sendRequest(String event) throws EventSendingFailureException {
        send(messageRequestQueueUrl, event);
    }

    public static class Builder {
        private final ProcessingRules deployProcessingRules;
        private ImplementationRunnerConfig config;

        public Builder() {
            deployProcessingRules = createDeployProcessingRules();
        }

        public Builder setConfig(ImplementationRunnerConfig config) {
            this.config = config;
            return this;
        }

        @SuppressWarnings("unused")
        public Builder withSolutionFor(String methodName, UserImplementation userImplementation) {
            deployProcessingRules
                    .on(methodName)
                    .call(userImplementation)
                    .build();
            return this;
        }

        public QueueBasedImplementationRunner create() {
            logToConsole("        QueueBasedImplementationRunner.Builder create");
            logToConsole("        QueueBasedImplementationRunner.Builder deployProcessingRules: " + deployProcessingRules);
            return new QueueBasedImplementationRunner(config, deployProcessingRules);
        }

        private static ProcessingRules createDeployProcessingRules() {
            logToConsole("        QueueBasedImplementationRunner.Builder createDeployProcessingRules [start]");
            ProcessingRules deployProcessingRules = new ProcessingRules();

            // Debt - we only need this to consume message from the server
            deployProcessingRules
                    .on("display_description")
                    .call(params -> "OK")
                    .build();

            logToConsole("        QueueBasedImplementationRunner.Builder createDeployProcessingRules [end]");
            return deployProcessingRules;
        }
    }

    public void run() {
        logToConsole("        QueueBasedImplementationRunner run [start]");
        audit.logLine("Starting client");

        batchOfMessagesToDeleteRequest.setEntries(Collections.emptyList());
        batchOfResponsesSendRequest.setEntries(Collections.emptyList());

        try {
            audit.logLine("Waiting for requests");
            logToConsole("     QueueBasedImplementationRunner receive [start]");
            List<Message> messages;
            boolean continueFetching;
            do {
                messages = client.receiveMessage(receiveRequestQueueMessage).getMessages();
                continueFetching = messages.size() > 0;
                if (continueFetching) {
                    logToConsole("     QueueBasedImplementationRunner messages: " + messages);
                    for (Message message : messages) {
                        logToConsole("message: " + message);
                        logToConsole("payload: " + message.getBody());

                        try {
                            logToConsole("        QueueBasedImplementationRunner applyProcessingRules [start]");
                            JsonNode jsonNode = mapper.readValue(message.getBody(), JsonNode.class);
                            String payload = jsonNode.asText();
                            JsonRpcRequest jsonRpcRequest = gson.fromJson(payload, JsonRpcRequest.class);

                            Request request = new Request(message, jsonRpcRequest);
                            audit.startLine();
                            audit.log(request);

                            //Obtain response from user
                            Response response = deployProcessingRules.getResponseFor(request);
                            audit.log(response);

                            //Act
                            if (response instanceof FatalErrorResponse) {
                                audit.endLine();
                                continueFetching = false;
                                break;
                            } else {
                                addResponseToBatch(response);
                                audit.endLine();

                                logToConsole("        QueueBasedImplementationRunner deleting consumed message");
                                addMessageToDeleteBatch(request.getOriginalMessage());
                            }
                        } catch (JsonSyntaxException e) {
                            logToConsole("     QueueBasedImplementationRunner did not complete reading all messages successfully, not deleting unsuccessful messages");
                            throw new DeserializationException("Invalid message format", e);
                        }
                    }
                }
            } while (continueFetching);
            logToConsole("        QueueBasedImplementationRunner run finished receiving and processing message");

            respondToRequests();
            deleteMessages();
        } catch (Exception e) {
            logToConsole("        QueueBasedImplementationRunner run [error]");
            String message = "There was a problem processing messages";
            LOGGER.error(message, e);
            audit.logException(message, e);
        }
        audit.logLine("Stopping client");
        audit.endLine();
        logToConsole("        QueueBasedImplementationRunner run [end]");
    }

    private void addMessageToDeleteBatch(Message message) {
        deleteMessageBatchRequestEntries.add(
                new DeleteMessageBatchRequestEntry()
                        .withId(message.getMessageId()).withReceiptHandle(message.getReceiptHandle())
        );
    }

    public int getRequestTimeoutMillis() {
        return config.getRequestTimeoutMillis();
    }

    private void addResponseToBatch(Response response) {
        logToConsole("     response: " + response);

        sendMessageBatchRequestEntries.add(
                new SendMessageBatchRequestEntry()
                        .withId(response.getId())
                        .withMessageBody(gson.toJson(response))
        );
    }

    private void respondToRequests() throws BrokerCommunicationException {
        logToConsole("     QueueBasedImplementationRunner respondToRequest [start]");
        try {
            batchOfResponsesSendRequest.setEntries(sendMessageBatchRequestEntries);
            client.sendMessageBatch(batchOfResponsesSendRequest);
        } catch (Exception ex) {
            logToConsole("     QueueBasedImplementationRunner respondToRequest [error]");
            throw new BrokerCommunicationException(ex);
        }
        logToConsole("     QueueBasedImplementationRunner respondToRequest [end]");
    }

    private void deleteMessages() {
        DeleteMessageBatchResult result = client.deleteMessageBatch(batchOfMessagesToDeleteRequest);

        List<String> failures = result.getFailed().stream().map(
                BatchResultErrorEntry::getId
        ).collect(Collectors.toList());

        if (failures.size() > 0) {
            audit.logLine("failed to delete: " + failures.toArray().toString());
        }
    }

    private void send(String queueUrl, Object object) throws EventSendingFailureException {
        try {
            SendMessageBatchRequest batchOfResponsesSendRequest =
                    new SendMessageBatchRequest().withQueueUrl(queueUrl);
            List<SendMessageBatchRequestEntry> sendMessageBatchRequestEntries = batchOfResponsesSendRequest.getEntries();

            sendMessageBatchRequestEntries.add(
                    new SendMessageBatchRequestEntry(
                            String.valueOf(object.hashCode()), mapper.writeValueAsString(object)
                    )
            );

            batchOfResponsesSendRequest.setEntries(sendMessageBatchRequestEntries);
            client.sendMessageBatch(batchOfResponsesSendRequest);
        } catch (Exception ex) {
            throw new EventSendingFailureException("Failed to send event " + object, ex);
        }
    }

    private static AmazonSQS createAWSClient(String serviceEndpoint, String signingRegion, String accessKey, String secretKey) {
        AwsClientBuilder.EndpointConfiguration endpointConfiguration =
                new AwsClientBuilder.EndpointConfiguration(serviceEndpoint, signingRegion);
        return AmazonSQSClientBuilder.standard()
                .withEndpointConfiguration(endpointConfiguration)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                .build();
    }

    private CreateQueueRequest createQueueRequest(String queueName) {
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("FifoQueue", "true");
        attributes.put("ContentBasedDeduplication", "true");

        logToConsole(" QueueBasedImplementationRunner createQueueRequest: " + queueName);
        return new CreateQueueRequest(queueName)
                .withAttributes(attributes);
    }

    private String createQueue(CreateQueueRequest queue) {
        return client.createQueue(queue).getQueueUrl();
    }

    public long getRequestQueueMessagesAvailableCount() {
        return getQueueSizeAttributes(messageRequestQueueUrl).getAvailable();
    }

    public long getRequestQueueMessagesConsumedCount() {
        return getQueueSizeAttributes(messageRequestQueueUrl).getNotVisible();
    }

    public long getResponseQueueMessagesConsumedCount() {
        return getQueueSizeAttributes(messageResponseQueueUrl).getNotVisible();
    }

    public long getResponseQueueMessagesReceivedCount() {
        return getQueueSizeAttributes(messageResponseQueueUrl).getAvailable();
    }

    private QueueSize getQueueSizeAttributes(String queueUrl) {
        GetQueueAttributesResult queueAttributes = client
                .getQueueAttributes(queueUrl, Collections.singletonList("All"));
        int available = Integer.parseInt(queueAttributes.getAttributes().get("ApproximateNumberOfMessages"));
        int notVisible = Integer.parseInt(queueAttributes.getAttributes().get("ApproximateNumberOfMessagesNotVisible"));
        int delayed = Integer.parseInt(queueAttributes.getAttributes().get("ApproximateNumberOfMessagesDelayed"));
        return new QueueSize(available, notVisible, delayed);
    }

    private void purgeQueue(CreateQueueRequest requestQueue) {
        String queueUrl = String.format("%s/queue/%s", serviceEndpoint, requestQueue.getQueueName());
        client.purgeQueue(new PurgeQueueRequest(queueUrl));
    }

    //~~~~ Utils
    private boolean isNumeric(Object value) {
        if (value == null) return false;
        return ((value instanceof Integer) || (value instanceof Double));
    }

    private static class Audit {
        private final AuditStream auditStream;
        private StringBuilder line;

        Audit(AuditStream auditStream) {
            this.auditStream = auditStream;
            startLine();
        }

        //~~~ Normal output

        void startLine() {
            line = new StringBuilder();
        }

        void log(Auditable auditable) {
            String text = auditable.getAuditText();
            if (!text.isEmpty() && line.length() > 0) {
                line.append(", ");
            }
            line.append(text);
        }

        void endLine() {
            auditStream.println(line.toString());
        }

        //~~~ Exception

        void logException(String message, Exception e) {
            startLine();
            line.append(message).append(": ").append(e.getMessage());
            endLine();
        }

        void logLine(String text) {
            startLine();
            this.line.append(text);
            endLine();
        }

    }

    private static void logToConsole(String s) {
        if (new File("DEBUG").exists()) {
            System.out.println(s);
        }
    }
}
