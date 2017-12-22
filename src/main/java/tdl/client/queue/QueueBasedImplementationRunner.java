package tdl.client.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tdl.client.queue.abstractions.Request;
import tdl.client.queue.abstractions.UserImplementation;
import tdl.client.queue.abstractions.response.Response;
import tdl.client.queue.actions.ClientAction;
import tdl.client.audit.AuditStream;
import tdl.client.audit.Auditable;
import tdl.client.queue.transport.BrokerCommunicationException;
import tdl.client.queue.transport.RemoteBroker;
import java.util.Optional;

import static tdl.client.queue.actions.ClientActions.publish;

public class QueueBasedImplementationRunner implements ImplementationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueueBasedImplementationRunner.class);
    private final Audit audit;
    private ProcessingRules deployProcessingRules;
    private ImplementationRunnerConfig config;

    QueueBasedImplementationRunner(ImplementationRunnerConfig config, ProcessingRules deployProcessingRules) {
        this.config = config;
        this.deployProcessingRules = deployProcessingRules;
        audit = new Audit(config.getAuditStream());
    }

    public static class Builder {
        private ProcessingRules deployProcessingRules;
        private ImplementationRunnerConfig config;

        public Builder() {
            deployProcessingRules = createDeployProcessingRules();
        }

        public Builder setConfig(ImplementationRunnerConfig config) {
            this.config = config;
            return this;
        }

        public Builder withSolutionFor(String methodName, UserImplementation userImplementation) {
            deployProcessingRules
                    .on(methodName)
                    .call(userImplementation)
                    .then(publish());
            return this;
        }

        public Builder withSolutionFor(String methodName, UserImplementation userImplementation, ClientAction action) {
            deployProcessingRules
                    .on(methodName)
                    .call(userImplementation)
                    .then(action);
            return this;
        }

        public QueueBasedImplementationRunner create() {
            return new QueueBasedImplementationRunner(config, deployProcessingRules);
        }

        private static ProcessingRules createDeployProcessingRules() {
            ProcessingRules deployProcessingRules = new ProcessingRules();

            // Debt - we only need this to consume message from the server
            deployProcessingRules
                    .on("display_description")
                    .call(params -> "OK")
                    .then(publish());

            return deployProcessingRules;
        }
    }

    public void run() {
        audit.logLine("Starting client");
        try (RemoteBroker remoteBroker = new RemoteBroker(config.getHostname(), config.getPort(), config.getUniqueId(), config.getRequestTimeoutMillis())){
            //Design: We use a while loop instead of an ActiveMQ MessageListener to process the messages in order
            audit.logLine("Waiting for requests");
            Optional<Request> request = remoteBroker.receive();
            while (request.isPresent()) {
                request = applyProcessingRules(request.get(), deployProcessingRules, remoteBroker);
            }
        } catch (Exception e) {
            String message = "There was a problem processing messages";
            LOGGER.error(message, e);
            audit.logException(message, e);
        }
        audit.logLine("Stopping client");
    }

    public int getRequestTimeoutMillis() {
        return config.getRequestTimeoutMillis();
    }

    private Optional<Request> applyProcessingRules(
            Request request, ProcessingRules processingRules, RemoteBroker remoteBroker)
            throws BrokerCommunicationException {
        audit.startLine();
        audit.log(request);

        //Obtain response from user
        Response response = processingRules.getResponseFor(request);
        audit.log(response);

        //Obtain action
        ClientAction clientAction = response.getClientAction();

        //Act
        clientAction.afterResponse(remoteBroker, request, response);
        audit.log(clientAction);
        audit.endLine();
        return clientAction.getNextRequest(remoteBroker);
    }


    //~~~~ Utils

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

}
