package tdl.runner;

import com.mashape.unirest.http.exceptions.UnirestException;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import tdl.client.runner.*;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.IsEqual.equalTo;


public class Steps {
    private WiremockProcess challengeServerStub;
    private RecordingServerStub recordingServerStub;
    private String challengeHostname;
    private String recordingHostname;
    private String implementationSolutionStub;
    private int port;
    private String[] userCommandLineArgs = new String[]{""};
    private BufferedReader reader;
    private PrintStream writer;
    private IConsoleOut consoleOut;

    // Given

    @Given("There is a challenge server running on \"([^\"]*)\" port (\\d+)$")
    public void setupServerWithSetup(String hostname, int port) throws UnirestException {
        this.challengeHostname = hostname;
        this.port = port;
        challengeServerStub = new WiremockProcess(hostname, port);
        challengeServerStub.reset();
    }

    @And("There is a recording server running on \"([^\"]*)\" port (\\d+)$")
    public void setupRecordingServerWithSetup(String hostname, int port) throws UnirestException {
        this.recordingHostname = hostname;
        recordingServerStub = new RecordingServerStub(hostname, port);
        recordingServerStub.reset();
    }

    class ServerConfig {
        String verb;
        String endpoint;
        int returnStatus;
        String returnBody;
    }

    @And("the challenge server exposes the following endpoints$")
    public void configureChallengeServerEndpoint(List<ServerConfig> configs) {
        for (ServerConfig config: configs) {
            challengeServerStub.createStubMappingWithUnicodeRegex(config.verb, config.endpoint, config.returnStatus, config.returnBody);
        }
    }

    @And("the recording server exposes the following endpoints$")
    public void configureRecordingServerEndpoint(List<ServerConfig> configs) {
        for (ServerConfig config: configs) {
            recordingServerStub.createStubMapping(config.verb, config.endpoint, config.returnStatus, config.returnBody);
        }
    }

    @And("the challenge server expects requests to have the Accept header set to \"([^\"]*)\"")
    public void configureAcceptHeader(String header) throws UnirestException {
        challengeServerStub.addHeaderToStubs(header);
    }

    @Given("server endpoint \"([^\"]*)\" returns \"([^\"]*)\"")
    public void setupServerEndpointResponse(String endpoint, String returnValue) {
        challengeServerStub.adjustStubMappingResponse(endpoint, returnValue);
    }

    @And("the challenges folder is empty")
    public void deleteContentsOfChallengesFolder() throws IOException {
        Path path =  Paths.get("challenges");
        deleteFolderContents(path.toFile());
    }

    void deleteFolderContents(File folder) {
        File[] files = folder.listFiles();
        if(files!=null) { //some JVMs return null for empty dirs
            for(File f: files) {
                if(f.isDirectory()) {
                    deleteFolderContents(f);
                } else {
                    f.delete();
                }
            }
        }
    }

    @Given("the action input comes from a provider returning \"([^\"]*)\"$")
    public void actionInputComesFromProviderReturning(String s) {
        userCommandLineArgs = new String[]{s};
    }

    @Given("^there is an implementation runner that prints \"([^\"]*)\"$")
    public void implementationRunnerPrinter(String s) {
        implementationSolutionStub = s;
    }


    // When
    @When("user starts client")
    public void userStartsChallenge() throws UnirestException {
        challengeServerStub.configureServer();
        recordingServerStub.configureServer();
        String journeyId = "dGRsLXRlc3QtY25vZGVqczAxfFNVTSxITE8sQ0hLfFE=";
        String username = "tdl-test-cnodejs01";

        consoleOut = new TestConsoleOut();
        ImplementationRunner implementationRunner;
        if (implementationSolutionStub != null && !implementationSolutionStub.equals("")) {
            implementationRunner = new NoisyImplementationRunner(implementationSolutionStub, consoleOut);
        } else {
            implementationRunner = new QuietImplementationRunner();
        }

        writer = new PrintStream(new BufferedOutputStream(System.out));
        reader = new BufferedReader(new InputStreamReader(System.in));
        ChallengeSession session = ChallengeSession.forUsername(username)
                .withServerHostname(challengeHostname)
                .withPort(port)
                .withJourneyId(journeyId)
                .withColours(true)
                .withBufferedReader(reader)
                .withConsoleOut(consoleOut)
                .withRecordingSystemOn(true)
                .withImplementationRunner(implementationRunner);

        session.start(userCommandLineArgs);
    }

    // Then

    @Then("the server interaction should look like:$")
    public void parseInput(String expectedOutput) throws IOException, InteractionException {
        String total = ((TestConsoleOut)consoleOut).getTotal();
        assertThat(expectedOutput, equalTo(total));
    }

    @And("the recording system should be notified with \"([^\"]*)\"$")
    public void parseInput2(String expectedOutput) throws IOException, InteractionException, UnirestException {
        recordingServerStub.verifyEndpointWasHit("notify", "POST", expectedOutput);

    }

    @Then("the file \"([^\"]*)\" should contain$")
    public void checkFileContainsDescription(String file, String text) throws IOException, InteractionException {
        BufferedReader inputReader = new BufferedReader(new FileReader(file));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = inputReader.readLine()) != null){
            content.append(line);
            content.append("\n");
        }
        String c = content.toString();
        assertThat("Contents of the file is not what is expected", c, equalTo(text));
    }

    @Then("the implementation runner should be run with the provided implementations")
    public void checkQueueClientRunningImplementation() throws InteractionException {
        String total = ((TestConsoleOut)consoleOut).getTotal();
        assertThat(total, containsString(implementationSolutionStub));
    }

    @Then("the client should not ask the user for input")
    public void checkClientDoesNotAskForInput() throws InteractionException {
        String total = ((TestConsoleOut)consoleOut).getTotal();
        assertThat(total, not(containsString("Selected action is:")));
    }

}
