package com.redhat.naps.process;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;

import static org.junit.Assert.assertEquals;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.After;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kie.kogito.kafka.KafkaClient;
import org.kie.kogito.testcontainers.quarkus.KafkaQuarkusTestResource;


import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import org.kie.kogito.services.event.AbstractProcessDataEvent;

import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Encounter;

// https://github.com/hapifhir/hapi-fhir/blob/master/hapi-fhir-base/src/main/java/ca/uhn/fhir/context/FhirContext.java
import ca.uhn.fhir.context.FhirContext;

/*
    Inspired by:  https://github.com/kiegroup/kogito-examples/blob/stable/process-kafka-quickstart-quarkus/src/test/java/org/acme/travel/MessagingIT.java
*/
@QuarkusTest
@QuarkusTestResource(KafkaQuarkusTestResource.class)
public class ObservationLifecycleTest {

    private static final String DATA = "data";
    private static final String KOGITO_PROCESS_INSTANCE_ID = "kogitoProcessinstanceId";
    private static final String KOGITO_PROCESS_ID = "kogitoProcessId";
    private static final String KOGITO_PROCESS_INSTANCE_STATE = "kogitoProcessinstanceState";

    private static final String TOPIC_OBSERVATION_EVENT = "topic-observation-event";
    private static final String TOPIC_OBSERVATION_COMMAND = "topic-observation-command";
    private static final String OBSERVATION_CREATED = "observation-created";

    private static Logger log = Logger.getLogger(ObservationLifecycleTest.class);

    private static String kogitoProcessInstanceId="";

    private static boolean proceed = false;

    private static FhirContext fhirCtx = FhirContext.forR4();

    private ObservationStatus expectedObsStatus=ObservationStatus.PRELIMINARY;
    
    @Inject
    private ObjectMapper objectMapper;
    
    public KafkaClient kafkaClient;
    
    @ConfigProperty(name = KafkaQuarkusTestResource.KOGITO_KAFKA_PROPERTY)
    private String kafkaBootstrapServers;

    private long sleepBetweenLockChecks=2000;

    @BeforeEach
    public void setup() {
        objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        objectMapper.registerModule(JsonFormat.getCloudEventJacksonModule());
    }

    @Test
    public void testFhirPayloadProcessing() {
        Observation obs = createInitialObservation();
        String obsString = fhirCtx.newJsonParser().setPrettyPrint(true).encodeResourceToString(obs);
        log.infov("testFhirPayloadProcessing() obsString: {0} ", obsString);

        obs = fhirCtx.newJsonParser().parseResource(Observation.class, obsString);
        assertEquals("#1", obs.getContained().get(0).getId());
    }

    @Test
    public void testObservationProcess() throws InterruptedException, JsonProcessingException {
        kafkaClient = new KafkaClient(kafkaBootstrapServers);
        Observation obs = createInitialObservation();

        kafkaClient.consume(TOPIC_OBSERVATION_COMMAND, iJson -> {
            try {
                JsonNode event = objectMapper.readValue(iJson, JsonNode.class);

                kogitoProcessInstanceId = event.get(KOGITO_PROCESS_INSTANCE_ID).asText();
                Observation obsReceived = objectMapper.readValue(event.get(DATA).toString(), Observation.class);
                log.infov("Received observation. pInstanceId: {0}, status: {1}", kogitoProcessInstanceId, obsReceived.getStatus().getDisplay());
                assertEquals(obsReceived.getStatus(), expectedObsStatus);
            } catch (Throwable e) {
                log.error("Error parsing {}", iJson, e);
                throw new RuntimeException(e);
            }finally {
                proceed = true;
            }
        });
        expectedObsStatus=ObservationStatus.PRELIMINARY;
        sendCloudEvent(obs, TOPIC_OBSERVATION_EVENT, OBSERVATION_CREATED );
    }

    private Observation createInitialObservation() {
        Observation obs = new Observation();

        Patient pt = new Patient();
        pt.setId("#1");
        pt.addName().setFamily("FAM");
        obs.getSubject().setReference("#1");
        obs.getContained().add(pt);

        Encounter enc = new Encounter();
        enc.setStatus(Encounter.EncounterStatus.ARRIVED);
        obs.getEncounter().setResource(enc);

        obs.setStatus(ObservationStatus.PRELIMINARY);

        return obs;
    }

    private String generateCloudEventJson(Observation obs, String obsTrigger) throws JsonProcessingException {

        String jsonObs = objectMapper.writeValueAsString(obs);

        CloudEvent cloudEvent = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create(""))
            .withType(obsTrigger) // evaluated as "trigger" by org.kie.kogito.event.impl.CloudEventConsumer; correspondes to "message" in intermediate message events
            .withTime(OffsetDateTime.now())
            .withData(jsonObs.getBytes())
            .withExtension("kogitoReferenceId", kogitoProcessInstanceId)
            .build();
      
        return objectMapper.writeValueAsString(cloudEvent);
    }
    
    private void sendCloudEvent(Observation obs, String topic, String obsTrigger) throws JsonProcessingException, InterruptedException {

        String oJson = generateCloudEventJson(obs, obsTrigger);
        kafkaClient.produce(oJson, topic);
        log.infov("Sent event w/ pInstanceId: {0}, obsTrigger: {1} to topic: {2}", kogitoProcessInstanceId, obsTrigger, topic);
        proceed = false;
        int x = 0;
        while(!proceed){
            if(x < 5) {
                Thread.sleep(sleepBetweenLockChecks);
                x++;
            }else {
                log.errorv("Stuck waiting for response from sent event w/ pInstanceId: {0}, obsTrigger: {1} to topic: {2}", kogitoProcessInstanceId, obsTrigger, topic);
                proceed=true;
            }
        }
    }

    @After
    public void stop() {
        Optional.ofNullable(kafkaClient).ifPresent(KafkaClient::shutdown);
    }
}
