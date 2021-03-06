/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.connect;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.HostAliasBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretKeySelectorBuilder;
import io.fabric8.kubernetes.api.model.SecretVolumeSourceBuilder;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.CertSecretSourceBuilder;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaConnectS2IResources;
import io.strimzi.api.kafka.model.KafkaConnector;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.PasswordSecretSourceBuilder;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.status.KafkaConnectStatus;
import io.strimzi.api.kafka.model.status.KafkaConnectorStatus;
import io.strimzi.api.kafka.model.template.DeploymentStrategy;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.Environment;
import io.strimzi.systemtest.annotations.OpenShiftOnly;
import io.strimzi.systemtest.kafkaclients.externalClients.BasicExternalKafkaClient;
import io.strimzi.systemtest.kafkaclients.internalClients.InternalKafkaClient;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaClientsResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectS2IResource;
import io.strimzi.systemtest.resources.crd.KafkaConnectorResource;
import io.strimzi.systemtest.resources.crd.KafkaResource;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.resources.crd.KafkaUserResource;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectS2IUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectorUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentConfigUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.DeploymentUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.test.TestUtils;
import io.vertx.core.json.JsonObject;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.CONNECT;
import static io.strimzi.systemtest.Constants.CONNECTOR_OPERATOR;
import static io.strimzi.systemtest.Constants.CONNECT_COMPONENTS;
import static io.strimzi.systemtest.Constants.EXTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.INTERNAL_CLIENTS_USED;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.SCALABILITY;
import static io.strimzi.systemtest.Constants.SMOKE;
import static io.strimzi.systemtest.enums.CustomResourceStatus.NotReady;
import static io.strimzi.systemtest.enums.CustomResourceStatus.Ready;
import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.valid4j.matchers.jsonpath.JsonPathMatchers.hasJsonPath;

@Tag(REGRESSION)
@Tag(CONNECT)
@Tag(CONNECT_COMPONENTS)
class ConnectST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(ConnectST.class);
    public static final String NAMESPACE = "connect-cluster-test";

    private static final String CONNECT_TOPIC_NAME = "connect-topic-example";

    private String kafkaClientsPodName;

    @Test
    void testDeployUndeploy() {
        Map<String, Object> exceptedConfig = StUtils.loadProperties("group.id=" + KafkaConnectResources.deploymentName(clusterName) + "\n" +
                "key.converter=org.apache.kafka.connect.json.JsonConverter\n" +
                "value.converter=org.apache.kafka.connect.json.JsonConverter\n" +
                "config.storage.topic=" + KafkaConnectResources.metricsAndLogConfigMapName(clusterName) + "\n" +
                "status.storage.topic=" + KafkaConnectResources.configStorageTopicStatus(clusterName) + "\n" +
                "offset.storage.topic=" + KafkaConnectResources.configStorageTopicOffsets(clusterName) + "\n");

        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1).build(), true);
        LOGGER.info("Looks like the connect cluster my-cluster deployed OK");

        String podName = PodUtils.getPodNameByPrefix(KafkaConnectResources.deploymentName(clusterName));
        String kafkaPodJson = TestUtils.toJsonString(kubeClient().getPod(podName));

        assertThat(kafkaPodJson, hasJsonPath(StUtils.globalVariableJsonPathBuilder(0, "KAFKA_CONNECT_BOOTSTRAP_SERVERS"),
                hasItem(KafkaResources.tlsBootstrapAddress(clusterName))));
        assertThat(StUtils.getPropertiesFromJson(0, kafkaPodJson, "KAFKA_CONNECT_CONFIGURATION"), is(exceptedConfig));
        testDockerImagesForKafkaConnect();

        verifyLabelsOnPods(clusterName, "connect", null, "KafkaConnect");
        verifyLabelsForService(clusterName, "connect-api", "KafkaConnect");
        verifyLabelsForConfigMaps(clusterName, null, "");
        verifyLabelsForServiceAccounts(clusterName, null);
    }

    private void testDockerImagesForKafkaConnect() {
        LOGGER.info("Verifying docker image names");
        Map<String, String> imgFromDeplConf = getImagesFromConfig();
        //Verifying docker image for kafka connect
        String connectImageName = PodUtils.getFirstContainerImageNameFromPod(kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).
                get(0).getMetadata().getName());

        String connectVersion = Crds.kafkaConnectOperation(kubeClient().getClient()).inNamespace(NAMESPACE).withName(clusterName).get().getSpec().getVersion();
        if (connectVersion == null) {
            connectVersion = Environment.ST_KAFKA_VERSION;
        }

        assertThat(TestUtils.parseImageMap(imgFromDeplConf.get(KAFKA_CONNECT_IMAGE_MAP)).get(connectVersion), is(connectImageName));
        LOGGER.info("Docker images verified");
    }

    @Test
    @Tag(SMOKE)
    @Tag(INTERNAL_CLIENTS_USED)
    void testKafkaConnectWithFileSinkPlugin() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, CONNECT_TOPIC_NAME).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editSpec()
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
            .endSpec()
            .build());

        String kafkaConnectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(kafkaConnectPodName);

        KafkaConnectorUtils.createFileSinkConnector(kafkaClientsPodName, CONNECT_TOPIC_NAME, Constants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(clusterName, NAMESPACE, 8083));

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(CONNECT_TOPIC_NAME)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.checkProducedAndConsumedMessages(
            internalKafkaClient.sendMessagesPlain(),
            internalKafkaClient.receiveMessagesPlain()
        );

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH, "99");
    }

    @Test
    @Tag(INTERNAL_CLIENTS_USED)
    void testKafkaConnectWithPlainAndScramShaAuthentication() {
        // Use a Kafka with plain listener disabled
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3)
                .editSpec()
                    .editKafka()
                        .withNewListeners()
                            .addNewGenericKafkaListener()
                                .withName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .endGenericKafkaListener()
                        .endListeners()
                    .endKafka()
                .endSpec()
                .build());

        KafkaUser kafkaUser = KafkaUserResource.create(KafkaUserResource.scramShaUser(clusterName, USER_NAME).build());

        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, CONNECT_TOPIC_NAME).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
                .withNewSpec()
                    .withBootstrapServers(KafkaResources.plainBootstrapAddress(clusterName))
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withNewUsername(USER_NAME)
                        .withPasswordSecret(new PasswordSecretSourceBuilder()
                            .withSecretName(USER_NAME)
                            .withPassword("password")
                            .build())
                    .endKafkaClientAuthenticationScramSha512()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withVersion(Environment.ST_KAFKA_VERSION)
                    .withReplicas(1)
                .endSpec()
                .build());

        String kafkaConnectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();
        String kafkaConnectLogs = kubeClient().logs(kafkaConnectPodName);

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(kafkaConnectPodName);

        LOGGER.info("Verifying that KafkaConnect pod logs don't contain ERRORs");
        assertThat(kafkaConnectLogs, not(containsString("ERROR")));

        LOGGER.info("Creating FileStreamSink connector via pod {} with topic {}", kafkaClientsPodName, CONNECT_TOPIC_NAME);
        KafkaConnectorUtils.createFileSinkConnector(kafkaClientsPodName, CONNECT_TOPIC_NAME, Constants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(clusterName, NAMESPACE, 8083));

        KafkaClientsResource.create(KafkaClientsResource.deployKafkaClients(false, kafkaClientsName + "-second", kafkaUser).build());

        final String kafkaClientsSecondPodName =
                ResourceManager.kubeClient().listPodsByPrefixInName(kafkaClientsName + "-second").get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsSecondPodName)
            .withTopicName(CONNECT_TOPIC_NAME)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withKafkaUsername(USER_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.checkProducedAndConsumedMessages(
                internalKafkaClient.sendMessagesPlain(),
                internalKafkaClient.receiveMessagesPlain()
        );

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH, "99");
    }

    @Test
    @Tag(CONNECTOR_OPERATOR)
    @Tag(INTERNAL_CLIENTS_USED)
    void testKafkaConnectAndConnectorFileSinkPlugin() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
                .editMetadata()
                    .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
                .endMetadata()
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                .endSpec()
                .build());

        String connectorName = "license-source";
        KafkaConnectorResource.create(KafkaConnectorResource.kafkaConnector(connectorName, clusterName, 2)
            .editSpec()
                .addToConfig("topic", TOPIC_NAME)
            .endSpec()
            .build());

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(TOPIC_NAME)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
            .build();

        int received = internalKafkaClient.receiveMessagesPlain();
        assertThat(received, greaterThanOrEqualTo(MESSAGE_COUNT));

        String service = KafkaConnectResources.url(clusterName, NAMESPACE, 8083);
        String output = cmdKubeClient().execInPod(kafkaClientsPodName, "/bin/bash", "-c", "curl " + service + "/connectors/" + connectorName).out();
        assertThat(output, containsString("\"name\":\"license-source\""));
        assertThat(output, containsString("\"connector.class\":\"org.apache.kafka.connect.file.FileStreamSourceConnector\""));
        assertThat(output, containsString("\"tasks.max\":\"2\""));
        assertThat(output, containsString("\"topic\":\"" + TOPIC_NAME + "\""));
    }


    @Test
    void testJvmAndResources() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        Map<String, String> jvmOptionsXX = new HashMap<>();
        jvmOptionsXX.put("UseG1GC", "true");

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editSpec()
                .withResources(new ResourceRequirementsBuilder()
                        .addToLimits("memory", new Quantity("400M"))
                        .addToLimits("cpu", new Quantity("2"))
                        .addToRequests("memory", new Quantity("300M"))
                        .addToRequests("cpu", new Quantity("1"))
                        .build())
                    .withNewJvmOptions()
                        .withXmx("200m")
                        .withXms("200m")
                        .withXx(jvmOptionsXX)
                    .endJvmOptions()
                .endSpec()
                .build());

        String podName = PodUtils.getPodNameByPrefix(KafkaConnectResources.deploymentName(clusterName));
        assertResources(NAMESPACE, podName, KafkaConnectResources.deploymentName(clusterName),
                "400M", "2", "300M", "1");
        assertExpectedJavaOpts(podName, KafkaConnectResources.deploymentName(clusterName),
                "-Xmx200m", "-Xms200m", "-XX:+UseG1GC");
    }

    @Test
    @Tag(SCALABILITY)
    void testKafkaConnectScaleUpScaleDown() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());
        LOGGER.info("Running kafkaConnectScaleUP {} in namespace", NAMESPACE);
        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1).build());

        String deploymentName = KafkaConnectResources.deploymentName(clusterName);

        // kafka cluster Connect already deployed
        List<String> connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        int initialReplicas = connectPods.size();
        assertThat(initialReplicas, is(1));
        final int scaleTo = initialReplicas + 3;

        LOGGER.info("Scaling up to {}", scaleTo);
        KafkaConnectResource.replaceKafkaConnectResource(clusterName, c -> c.getSpec().setReplicas(scaleTo));

        DeploymentUtils.waitForDeploymentAndPodsReady(deploymentName, scaleTo);
        connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        assertThat(connectPods.size(), is(scaleTo));

        LOGGER.info("Scaling down to {}", initialReplicas);
        KafkaConnectResource.replaceKafkaConnectResource(clusterName, c -> c.getSpec().setReplicas(initialReplicas));

        DeploymentUtils.waitForDeploymentAndPodsReady(deploymentName, initialReplicas);
        connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        assertThat(connectPods.size(), is(initialReplicas));
    }

    @Test
    @Tag(INTERNAL_CLIENTS_USED)
    void testSecretsWithKafkaConnectWithTlsAndTlsClientAuthentication() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3)
                .editSpec()
                    .editKafka()
                        .withNewListeners()
                            .addNewGenericKafkaListener()
                                .withName(Constants.TLS_LISTENER_DEFAULT_NAME)
                                .withPort(9093)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
                                .withAuth(new KafkaListenerAuthenticationTls())
                            .endGenericKafkaListener()
                        .endListeners()
                    .endKafka()
                .endSpec()
                .build());

        KafkaUser kafkaUser = KafkaUserResource.create(KafkaUserResource.tlsUser(clusterName, USER_NAME).build());

        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, CONNECT_TOPIC_NAME).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewTls()
                        .addNewTrustedCertificate()
                            .withSecretName(clusterName + "-cluster-ca-cert")
                            .withCertificate("ca.crt")
                        .endTrustedCertificate()
                    .endTls()
                    .withBootstrapServers(clusterName + "-kafka-bootstrap:9093")
                    .withNewKafkaClientAuthenticationTls()
                        .withNewCertificateAndKey()
                            .withSecretName(USER_NAME)
                            .withCertificate("user.crt")
                            .withKey("user.key")
                        .endCertificateAndKey()
                    .endKafkaClientAuthenticationTls()
                .endSpec()
                .build());

        String kafkaConnectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();
        String kafkaConnectLogs = kubeClient().logs(kafkaConnectPodName);

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(kafkaConnectPodName);

        LOGGER.info("Verifying that KafkaConnect pod logs don't contain ERRORs");
        assertThat(kafkaConnectLogs, not(containsString("ERROR")));

        LOGGER.info("Creating FileStreamSink connector via pod {} with topic {}", kafkaClientsPodName, CONNECT_TOPIC_NAME);
        KafkaConnectorUtils.createFileSinkConnector(kafkaClientsPodName, CONNECT_TOPIC_NAME, Constants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(clusterName, NAMESPACE, 8083));

        KafkaClientsResource.create(KafkaClientsResource.deployKafkaClients(true, kafkaClientsName + "-second", kafkaUser).build());

        final String kafkaClientsSecondPodName =
            ResourceManager.kubeClient().listPodsByPrefixInName(kafkaClientsName + "-second").get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsSecondPodName)
            .withTopicName(CONNECT_TOPIC_NAME)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withKafkaUsername(USER_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.checkProducedAndConsumedMessages(
                internalKafkaClient.sendMessagesTls(),
                internalKafkaClient.receiveMessagesTls()
        );

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH, "99");
    }

    @Test
    @Tag(INTERNAL_CLIENTS_USED)
    void testSecretsWithKafkaConnectWithTlsAndScramShaAuthentication() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3)
            .editSpec()
                .editKafka()
                    .withNewListeners()
                        .addNewGenericKafkaListener()
                            .withName(Constants.TLS_LISTENER_DEFAULT_NAME)
                            .withPort(9093)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(true)
                            .withAuth(new KafkaListenerAuthenticationScramSha512())
                        .endGenericKafkaListener()
                    .endListeners()
                .endKafka()
            .endSpec()
            .build());

        KafkaUser kafkaUser = KafkaUserResource.create(KafkaUserResource.scramShaUser(clusterName, USER_NAME).build());

        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, CONNECT_TOPIC_NAME).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewTls()
                        .addNewTrustedCertificate()
                            .withSecretName(clusterName + "-cluster-ca-cert")
                            .withCertificate("ca.crt")
                        .endTrustedCertificate()
                    .endTls()
                    .withBootstrapServers(clusterName + "-kafka-bootstrap:9093")
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withUsername(USER_NAME)
                        .withNewPasswordSecret()
                            .withSecretName(USER_NAME)
                            .withPassword("password")
                        .endPasswordSecret()
                    .endKafkaClientAuthenticationScramSha512()
                .endSpec()
                .build());

        String kafkaConnectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();
        String kafkaConnectLogs = kubeClient().logs(kafkaConnectPodName);

        LOGGER.info("Verifying that KafkaConnect pod logs don't contain ERRORs");
        assertThat(kafkaConnectLogs, not(containsString("ERROR")));

        LOGGER.info("Creating FileStreamSink connector via pod {} with topic {}", kafkaClientsPodName, CONNECT_TOPIC_NAME);
        KafkaConnectorUtils.createFileSinkConnector(kafkaClientsPodName, CONNECT_TOPIC_NAME, Constants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(clusterName, NAMESPACE, 8083));

        KafkaClientsResource.create(KafkaClientsResource.deployKafkaClients(true, kafkaClientsName + "-second", kafkaUser).build());

        final String kafkaClientsSecondPodName =
            ResourceManager.kubeClient().listPodsByPrefixInName(kafkaClientsName + "-second").get(0).getMetadata().getName();

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsSecondPodName)
            .withTopicName(CONNECT_TOPIC_NAME)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withKafkaUsername(USER_NAME)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.TLS_LISTENER_DEFAULT_NAME)
            .build();

        internalKafkaClient.checkProducedAndConsumedMessages(
                internalKafkaClient.sendMessagesTls(),
                internalKafkaClient.receiveMessagesTls()
        );

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH, "99");
    }

    @Test
    void testCustomAndUpdatedValues() {
        String usedVariable = "KAFKA_CONNECT_CONFIGURATION";

        LinkedHashMap<String, String> envVarGeneral = new LinkedHashMap<>();
        envVarGeneral.put("TEST_ENV_1", "test.env.one");
        envVarGeneral.put("TEST_ENV_2", "test.env.two");
        envVarGeneral.put(usedVariable, "test.value");

        LinkedHashMap<String, String> envVarUpdated = new LinkedHashMap<>();
        envVarUpdated.put("TEST_ENV_2", "updated.test.env.two");
        envVarUpdated.put("TEST_ENV_3", "test.env.three");

        Map<String, Object> connectConfig = new HashMap<>();
        connectConfig.put("config.storage.replication.factor", "1");
        connectConfig.put("offset.storage.replication.factor", "1");
        connectConfig.put("status.storage.replication.factor", "1");

        int initialDelaySeconds = 30;
        int timeoutSeconds = 10;
        int updatedInitialDelaySeconds = 31;
        int updatedTimeoutSeconds = 11;
        int periodSeconds = 10;
        int successThreshold = 1;
        int failureThreshold = 3;
        int updatedPeriodSeconds = 5;
        int updatedFailureThreshold = 1;

        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3, 1).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editSpec()
                .withNewTemplate()
                    .withNewConnectContainer()
                        .withEnv(StUtils.createContainerEnvVarsFromMap(envVarGeneral))
                    .endConnectContainer()
                .endTemplate()
                .withNewReadinessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endReadinessProbe()
                .withNewLivenessProbe()
                    .withInitialDelaySeconds(initialDelaySeconds)
                    .withTimeoutSeconds(timeoutSeconds)
                    .withPeriodSeconds(periodSeconds)
                    .withSuccessThreshold(successThreshold)
                    .withFailureThreshold(failureThreshold)
                .endLivenessProbe()
            .endSpec()
            .build());

        Map<String, String> connectSnapshot = DeploymentUtils.depSnapshot(KafkaConnectResources.deploymentName(clusterName));

        // Remove variable which is already in use
        envVarGeneral.remove(usedVariable);
        LOGGER.info("Verify values before update");
        checkReadinessLivenessProbe(KafkaConnectResources.deploymentName(clusterName), KafkaConnectResources.deploymentName(clusterName), initialDelaySeconds, timeoutSeconds,
                periodSeconds, successThreshold, failureThreshold);
        checkSpecificVariablesInContainer(KafkaConnectResources.deploymentName(clusterName), KafkaConnectResources.deploymentName(clusterName), envVarGeneral);

        LOGGER.info("Check if actual env variable {} has different value than {}", usedVariable, "test.value");
        assertThat(
                StUtils.checkEnvVarInPod(kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName(), usedVariable),
                is(not("test.value"))
        );

        LOGGER.info("Updating values in MirrorMaker container");
        KafkaConnectResource.replaceKafkaConnectResource(clusterName, kc -> {
            kc.getSpec().getTemplate().getConnectContainer().setEnv(StUtils.createContainerEnvVarsFromMap(envVarUpdated));
            kc.getSpec().setConfig(connectConfig);
            kc.getSpec().getLivenessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kc.getSpec().getReadinessProbe().setInitialDelaySeconds(updatedInitialDelaySeconds);
            kc.getSpec().getLivenessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kc.getSpec().getReadinessProbe().setTimeoutSeconds(updatedTimeoutSeconds);
            kc.getSpec().getLivenessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kc.getSpec().getReadinessProbe().setPeriodSeconds(updatedPeriodSeconds);
            kc.getSpec().getLivenessProbe().setFailureThreshold(updatedFailureThreshold);
            kc.getSpec().getReadinessProbe().setFailureThreshold(updatedFailureThreshold);
        });

        DeploymentUtils.waitTillDepHasRolled(KafkaConnectResources.deploymentName(clusterName), 1, connectSnapshot);

        LOGGER.info("Verify values after update");
        checkReadinessLivenessProbe(KafkaConnectResources.deploymentName(clusterName), KafkaConnectResources.deploymentName(clusterName), updatedInitialDelaySeconds, updatedTimeoutSeconds,
                updatedPeriodSeconds, successThreshold, updatedFailureThreshold);
        checkSpecificVariablesInContainer(KafkaConnectResources.deploymentName(clusterName), KafkaConnectResources.deploymentName(clusterName), envVarUpdated);
        checkComponentConfiguration(KafkaConnectResources.deploymentName(clusterName), KafkaConnectResources.deploymentName(clusterName), "KAFKA_CONNECT_CONFIGURATION", connectConfig);
    }

    @Test
    @Tag(CONNECTOR_OPERATOR)
    @OpenShiftOnly
    void testKafkaConnectorWithConnectAndConnectS2IWithSameName() {
        String connectClusterName = "connect-cluster";
        String connectS2IClusterName = "connect-s2i-cluster";

        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());
        // Crate connect cluster with default connect image
        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .addToConfig("group.id", connectClusterName)
                .addToConfig("offset.storage.topic", connectClusterName + "-offsets")
                .addToConfig("config.storage.topic", connectClusterName + "-config")
                .addToConfig("status.storage.topic", connectClusterName + "-status")
            .endSpec()
            .build());

        // Create different connect cluster via S2I resources
        KafkaConnectS2IResource.kafkaConnectS2IWithoutWait(KafkaConnectS2IResource.kafkaConnectS2I(clusterName, clusterName, 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .addToConfig("group.id", connectS2IClusterName)
                .addToConfig("offset.storage.topic", connectS2IClusterName + "-offsets")
                .addToConfig("config.storage.topic", connectS2IClusterName + "-config")
                .addToConfig("status.storage.topic", connectS2IClusterName + "-status")
            .endSpec()
            .build());

        KafkaConnectS2IUtils.waitForConnectS2INotReady(clusterName);

        KafkaConnectorResource.create(KafkaConnectorResource.kafkaConnector(clusterName)
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("topics", TOPIC_NAME)
                .addToConfig("file", "/tmp/test-file-sink.txt")
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
            .endSpec()
            .build());

        // Check that KafkaConnect contains created connector
        String connectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();
        KafkaConnectorUtils.waitForConnectorCreation(connectPodName, clusterName);

        KafkaConnectS2IUtils.waitForConnectS2INotReady(clusterName);

        String newTopic = "new-topic";
        String connectorConfig = KafkaConnectorUtils.getConnectorConfig(connectPodName, clusterName, "localhost");

        KafkaConnectorResource.replaceKafkaConnectorResource(clusterName, kc -> {
            kc.getSpec().getConfig().put("topics", newTopic);
            kc.getSpec().setTasksMax(8);
        });

        connectorConfig = KafkaConnectorUtils.waitForConnectorConfigUpdate(connectPodName, clusterName, connectorConfig, "localhost");
        assertThat(connectorConfig.contains("tasks.max\":\"8"), is(true));
        assertThat(connectorConfig.contains("topics\":\"" + newTopic), is(true));

        // Now delete KafkaConnector resource and create connector manually
        KafkaConnectorResource.kafkaConnectorClient().inNamespace(NAMESPACE).withName(clusterName).delete();

        KafkaConnectResource.replaceKafkaConnectResource(clusterName, kc -> {
            kc.getMetadata().getAnnotations().remove(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES);
        });

        KafkaConnectorUtils.createFileSinkConnector(kafkaClientsPodName, TOPIC_NAME, Constants.DEFAULT_SINK_FILE_PATH, KafkaConnectResources.url(clusterName, NAMESPACE, 8083));
        final String connectorName = "sink-test";
        KafkaConnectorUtils.waitForConnectorCreation(connectPodName, connectorName);
        KafkaConnectorUtils.waitForConnectorStability(connectorName, connectPodName);
        KafkaConnectS2IUtils.waitForConnectS2INotReady(clusterName);

        KafkaConnectS2IResource.kafkaConnectS2IClient().inNamespace(NAMESPACE).withName(clusterName).delete();
        DeploymentConfigUtils.waitForDeploymentConfigDeletion(KafkaConnectS2IResources.deploymentName(clusterName));
    }

    @Test
    @Tag(CONNECTOR_OPERATOR)
    @Tag(INTERNAL_CLIENTS_USED)
    @Tag(ACCEPTANCE)
    void testMultiNodeKafkaConnectWithConnectorCreation() {
        String connectClusterName = "connect-cluster";

        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());
        // Crate connect cluster with default connect image
        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 3)
                .editMetadata()
                    .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
                .endMetadata()
                .editSpec()
                    .addToConfig("group.id", connectClusterName)
                    .addToConfig("offset.storage.topic", connectClusterName + "-offsets")
                    .addToConfig("config.storage.topic", connectClusterName + "-config")
                    .addToConfig("status.storage.topic", connectClusterName + "-status")
                .endSpec()
                .build());

        KafkaConnectorResource.create(KafkaConnectorResource.kafkaConnector(clusterName)
                .editSpec()
                    .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                    .addToConfig("topics", TOPIC_NAME)
                    .addToConfig("file", Constants.DEFAULT_SINK_FILE_PATH)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .endSpec()
                .build());

        InternalKafkaClient internalKafkaClient = new InternalKafkaClient.Builder()
            .withUsingPodName(kafkaClientsPodName)
            .withTopicName(TOPIC_NAME)
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withMessageCount(MESSAGE_COUNT)
            .withListenerName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
            .build();

        String execConnectPod =  kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();
        JsonObject connectStatus = new JsonObject(cmdKubeClient().execInPod(
                execConnectPod,
                "curl", "-X", "GET", "http://localhost:8083/connectors/" + clusterName + "/status").out()
        );
        String podIP = connectStatus.getJsonObject("connector").getString("worker_id").split(":")[0];
        String connectorPodName = kubeClient().listPods().stream().filter(pod ->
                pod.getStatus().getPodIP().equals(podIP)).findFirst().get().getMetadata().getName();

        internalKafkaClient.assertSentAndReceivedMessages(
            internalKafkaClient.sendMessagesPlain(),
            internalKafkaClient.receiveMessagesPlain()
        );

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(connectorPodName, Constants.DEFAULT_SINK_FILE_PATH, "99");
    }

    @Tag(NODEPORT_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    @Tag(CONNECTOR_OPERATOR)
    @Test
    void testConnectTlsAuthWithWeirdUserName() {
        // Create weird named user with . and maximum of 64 chars -> TLS
        String weirdUserName = "jjglmahyijoambryleyxjjglmahy.ijoambryleyxjjglmahyijoambryleyxasd";

        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3)
                .editSpec()
                    .editKafka()
                        .withNewListeners()
                            .addNewGenericKafkaListener()
                                .withName(Constants.TLS_LISTENER_DEFAULT_NAME)
                                .withPort(9093)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
                                .withAuth(new KafkaListenerAuthenticationTls())
                            .endGenericKafkaListener()
                            .addNewGenericKafkaListener()
                                .withName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                                .withPort(9094)
                                .withType(KafkaListenerType.NODEPORT)
                                .withTls(true)
                                .withAuth(new KafkaListenerAuthenticationTls())
                            .endGenericKafkaListener()
                        .endListeners()
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, TOPIC_NAME).build());
        KafkaUserResource.create(KafkaUserResource.tlsUser(clusterName, weirdUserName).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
                .editMetadata()
                    .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
                .endMetadata()
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewTls()
                        .withTrustedCertificates(new CertSecretSourceBuilder()
                            .withCertificate("ca.crt")
                            .withNewSecretName(KafkaResources.clusterCaCertificateSecretName(clusterName))
                            .build())
                    .endTls()
                    .withNewKafkaClientAuthenticationTls()
                        .withNewCertificateAndKey()
                            .withSecretName(weirdUserName)
                            .withCertificate("user.crt")
                            .withKey("user.key")
                        .endCertificateAndKey()
                    .endKafkaClientAuthenticationTls()
                    .withBootstrapServers(KafkaResources.tlsBootstrapAddress(clusterName))
                .endSpec()
                .build());

        testConnectAuthorizationWithWeirdUserName(weirdUserName, SecurityProtocol.SSL);
    }

    @Tag(NODEPORT_SUPPORTED)
    @Tag(EXTERNAL_CLIENTS_USED)
    @Tag(CONNECTOR_OPERATOR)
    @Test
    void testConnectScramShaAuthWithWeirdUserName() {
        // Create weird named user with . and more than 64 chars -> SCRAM-SHA
        String weirdUserName = "jjglmahyijoambryleyxjjglmahy.ijoambryleyxjjglmahyijoambryleyxasdsadasdasdasdasdgasgadfasdad";

        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3)
                .editSpec()
                    .editKafka()
                        .withNewListeners()
                            .addNewGenericKafkaListener()
                                .withName(Constants.TLS_LISTENER_DEFAULT_NAME)
                                .withPort(9093)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(true)
                                .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .endGenericKafkaListener()
                            .addNewGenericKafkaListener()
                                .withName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
                                .withPort(9094)
                                .withType(KafkaListenerType.NODEPORT)
                                .withTls(true)
                                .withAuth(new KafkaListenerAuthenticationScramSha512())
                            .endGenericKafkaListener()
                        .endListeners()
                    .endKafka()
                .endSpec()
                .build());

        KafkaTopicResource.create(KafkaTopicResource.topic(clusterName, TOPIC_NAME).build());
        KafkaUserResource.create(KafkaUserResource.scramShaUser(clusterName, weirdUserName).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
                .editMetadata()
                    .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
                .endMetadata()
                .editOrNewSpec()
                    .withBootstrapServers(KafkaResources.tlsBootstrapAddress(clusterName))
                    .withNewKafkaClientAuthenticationScramSha512()
                        .withNewUsername(weirdUserName)
                        .withPasswordSecret(new PasswordSecretSourceBuilder()
                            .withSecretName(weirdUserName)
                            .withPassword("password")
                            .build())
                    .endKafkaClientAuthenticationScramSha512()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .withNewTls()
                        .withTrustedCertificates(new CertSecretSourceBuilder()
                                .withCertificate("ca.crt")
                                .withNewSecretName(KafkaResources.clusterCaCertificateSecretName(clusterName))
                                .build())
                    .endTls()
                .endSpec()
                .build());

        testConnectAuthorizationWithWeirdUserName(weirdUserName, SecurityProtocol.SASL_SSL);
    }

    void testConnectAuthorizationWithWeirdUserName(String userName, SecurityProtocol securityProtocol) {
        String connectorPodName = kubeClient().listPodsByPrefixInName(clusterName + "-connect").get(0).getMetadata().getName();

        KafkaConnectorResource.create(KafkaConnectorResource.kafkaConnector(clusterName)
                .editSpec()
                    .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                    .addToConfig("topics", TOPIC_NAME)
                    .addToConfig("file", Constants.DEFAULT_SINK_FILE_PATH)
                .endSpec()
                .build());

        BasicExternalKafkaClient basicExternalKafkaClient = new BasicExternalKafkaClient.Builder()
            .withNamespaceName(NAMESPACE)
            .withClusterName(clusterName)
            .withKafkaUsername(userName)
            .withMessageCount(MESSAGE_COUNT)
            .withSecurityProtocol(securityProtocol)
            .withTopicName(TOPIC_NAME)
            .withListenerName(Constants.EXTERNAL_LISTENER_DEFAULT_NAME)
            .build();

        assertThat(basicExternalKafkaClient.sendMessagesTls(), is(MESSAGE_COUNT));

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(connectorPodName, Constants.DEFAULT_SINK_FILE_PATH);
    }

    @Test
    @Tag(SCALABILITY)
    void testScaleConnectWithoutConnectorToZero() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 2).build());

        String connectDeploymentName = KafkaConnectResources.deploymentName(clusterName);
        List<String> connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);

        assertThat(connectPods.size(), is(2));
        //scale down
        LOGGER.info("Scaling KafkaConnect down to zero");
        KafkaConnectResource.replaceKafkaConnectResource(clusterName, kafkaConnect -> kafkaConnect.getSpec().setReplicas(0));

        KafkaConnectUtils.waitForConnectReady(clusterName);
        PodUtils.waitForPodsReady(kubeClient().getDeploymentSelectors(connectDeploymentName), 0, true);

        connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        KafkaConnectStatus connectStatus = KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get().getStatus();

        assertThat(connectPods.size(), is(0));
        assertThat(connectStatus.getConditions().get(0).getType(), is(Ready.toString()));
    }

    @Test
    @Tag(SCALABILITY)
    @Tag(CONNECTOR_OPERATOR)
    void testScaleConnectWithConnectorToZero() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 2)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .build());

        KafkaConnectorResource.create(KafkaConnectorResource.kafkaConnector(clusterName)
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("file", Constants.DEFAULT_SINK_FILE_PATH)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("topics", TOPIC_NAME)
            .endSpec()
            .build());

        String connectDeploymentName = KafkaConnectResources.deploymentName(clusterName);
        List<String> connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);

        assertThat(connectPods.size(), is(2));
        //scale down
        LOGGER.info("Scaling KafkaConnect down to zero");
        KafkaConnectResource.replaceKafkaConnectResource(clusterName, kafkaConnect -> kafkaConnect.getSpec().setReplicas(0));

        KafkaConnectUtils.waitForConnectReady(clusterName);
        PodUtils.waitForPodsReady(kubeClient().getDeploymentSelectors(connectDeploymentName), 0, true);

        connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        KafkaConnectStatus connectStatus = KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get().getStatus();
        KafkaConnectorStatus connectorStatus = KafkaConnectorResource.kafkaConnectorClient().inNamespace(NAMESPACE).withName(clusterName).get().getStatus();

        assertThat(connectPods.size(), is(0));
        assertThat(connectStatus.getConditions().get(0).getType(), is(Ready.toString()));
        assertThat(connectorStatus.getConditions().stream().anyMatch(condition -> condition.getType().equals(NotReady.toString())), is(true));
        assertThat(connectorStatus.getConditions().stream().anyMatch(condition -> condition.getMessage().contains("has 0 replicas")), is(true));
    }

    @Test
    @Tag(SCALABILITY)
    @Tag(CONNECTOR_OPERATOR)
    void testScaleConnectAndConnectorSubresource() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .build());

        KafkaConnectorResource.create(KafkaConnectorResource.kafkaConnector(clusterName)
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("file", Constants.DEFAULT_SINK_FILE_PATH)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("topics", TOPIC_NAME)
            .endSpec()
            .build());

        int scaleTo = 4;
        long connectObsGen = KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get().getStatus().getObservedGeneration();
        String connectGenName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getGenerateName();

        LOGGER.info("-------> Scaling KafkaConnect subresource <-------");
        LOGGER.info("Scaling subresource replicas to {}", scaleTo);
        cmdKubeClient().scaleByName(KafkaConnect.RESOURCE_KIND, clusterName, scaleTo);
        DeploymentUtils.waitForDeploymentAndPodsReady(KafkaConnectResources.deploymentName(clusterName), scaleTo);

        LOGGER.info("Check if replicas is set to {}, observed generation is higher - for spec and status - naming prefix should be same", scaleTo);
        List<String> connectPods = kubeClient().listPodNames(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND);
        assertThat(connectPods.size(), is(4));
        assertThat(KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get().getSpec().getReplicas(), is(4));
        assertThat(KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get().getStatus().getReplicas(), is(4));
        /*
        observed generation should be higher than before scaling -> after change of spec and successful reconciliation,
        the observed generation is increased
        */
        assertThat(connectObsGen < KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get().getStatus().getObservedGeneration(), is(true));
        for (String pod : connectPods) {
            assertThat(pod.contains(connectGenName), is(true));
        }

        LOGGER.info("-------> Scaling KafkaConnector subresource <-------");
        LOGGER.info("Scaling subresource task max to {}", scaleTo);
        cmdKubeClient().scaleByName(KafkaConnector.RESOURCE_KIND, clusterName, scaleTo);
        KafkaConnectorUtils.waitForConnectorsTaskMaxChange(clusterName, scaleTo);

        LOGGER.info("Check if taskMax is set to {}", scaleTo);
        assertThat(KafkaConnectorResource.kafkaConnectorClient().inNamespace(NAMESPACE).withName(clusterName).get().getSpec().getTasksMax(), is(scaleTo));
        assertThat(KafkaConnectorResource.kafkaConnectorClient().inNamespace(NAMESPACE).withName(clusterName).get().getStatus().getTasksMax(), is(scaleTo));

        LOGGER.info("Check taskMax on Connect pods API");
        for (String pod : connectPods) {
            JsonObject json = new JsonObject(KafkaConnectorUtils.getConnectorSpecFromConnectAPI(pod, clusterName));
            assertThat(Integer.parseInt(json.getJsonObject("config").getString("tasks.max")), is(scaleTo));
        }
    }

    @Test
    @SuppressWarnings({"checkstyle:MethodLength"})
    void testMountingSecretAndConfigMapAsVolumesAndEnvVars() {
        String secretPassword = "password";
        String encodedPassword = Base64.getEncoder().encodeToString(secretPassword.getBytes());

        String secretEnv = "MY_CONNECT_SECRET";
        String configMapEnv = "MY_CONNECT_CONFIG_MAP";

        String dotedSecretEnv = "MY_DOTED_CONNECT_SECRET";
        String dotedConfigMapEnv = "MY_DOTED_CONNECT_CONFIG_MAP";

        String configMapName = "connect-config-map";
        String secretName = "connect-secret";

        String dotedConfigMapName = "connect.config.map";
        String dotedSecretName = "connect.secret";

        String configMapKey = "my-key";
        String secretKey = "my-secret-key";

        String configMapValue = "my-value";

        Secret connectSecret = new SecretBuilder()
            .withNewMetadata()
                .withName(secretName)
            .endMetadata()
            .withType("Opaque")
            .addToData(secretKey, encodedPassword)
            .build();

        ConfigMap configMap = new ConfigMapBuilder()
            .editOrNewMetadata()
                .withName(configMapName)
            .endMetadata()
            .addToData(configMapKey, configMapValue)
            .build();

        Secret dotedConnectSecret = new SecretBuilder()
            .withNewMetadata()
                .withName(dotedSecretName)
            .endMetadata()
            .withType("Opaque")
            .addToData(secretKey, encodedPassword)
            .build();

        ConfigMap dotedConfigMap = new ConfigMapBuilder()
            .editOrNewMetadata()
                .withName(dotedConfigMapName)
            .endMetadata()
            .addToData(configMapKey, configMapValue)
            .build();

        kubeClient().createSecret(connectSecret);
        kubeClient().createSecret(dotedConnectSecret);
        kubeClient().getClient().configMaps().inNamespace(NAMESPACE).createOrReplace(configMap);
        kubeClient().getClient().configMaps().inNamespace(NAMESPACE).createOrReplace(dotedConfigMap);

        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editMetadata()
                .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
            .endMetadata()
            .editSpec()
                .withNewExternalConfiguration()
                    .addNewVolume()
                        .withNewName(secretName)
                        .withSecret(new SecretVolumeSourceBuilder().withSecretName(secretName).build())
                    .endVolume()
                    .addNewVolume()
                        .withNewName(configMapName)
                        .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(configMapName).build())
                    .endVolume()
                    .addNewVolume()
                        .withNewName(dotedSecretName)
                        .withSecret(new SecretVolumeSourceBuilder().withSecretName(dotedSecretName).build())
                    .endVolume()
                    .addNewVolume()
                        .withNewName(dotedConfigMapName)
                        .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(dotedConfigMapName).build())
                    .endVolume()
                    .addNewEnv()
                        .withNewName(secretEnv)
                        .withNewValueFrom()
                            .withSecretKeyRef(
                                new SecretKeySelectorBuilder()
                                    .withKey(secretKey)
                                    .withName(connectSecret.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                    .addNewEnv()
                        .withNewName(configMapEnv)
                        .withNewValueFrom()
                            .withConfigMapKeyRef(
                                new ConfigMapKeySelectorBuilder()
                                    .withKey(configMapKey)
                                    .withName(configMap.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                    .addNewEnv()
                        .withNewName(dotedSecretEnv)
                        .withNewValueFrom()
                            .withSecretKeyRef(
                                new SecretKeySelectorBuilder()
                                    .withKey(secretKey)
                                    .withName(dotedConnectSecret.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                    .addNewEnv()
                        .withNewName(dotedConfigMapEnv)
                        .withNewValueFrom()
                            .withConfigMapKeyRef(
                                new ConfigMapKeySelectorBuilder()
                                    .withKey(configMapKey)
                                    .withName(dotedConfigMap.getMetadata().getName())
                                    .withOptional(false)
                                    .build())
                        .endValueFrom()
                    .endEnv()
                .endExternalConfiguration()
            .endSpec()
            .build());

        String connectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();

        LOGGER.info("Check if the ENVs contains desired values");
        assertThat(cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "printenv " + secretEnv).out().trim(), equalTo(secretPassword));
        assertThat(cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "printenv " + configMapEnv).out().trim(), equalTo(configMapValue));
        assertThat(cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "printenv " + dotedSecretEnv).out().trim(), equalTo(secretPassword));
        assertThat(cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "printenv " + dotedConfigMapEnv).out().trim(), equalTo(configMapValue));

        LOGGER.info("Check if volumes contains desired values");
        assertThat(
            cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + configMapName + "/" + configMapKey).out().trim(),
            equalTo(configMapValue)
        );
        assertThat(
            cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + secretName + "/" + secretKey).out().trim(),
            equalTo(secretPassword)
        );
        assertThat(
            cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + dotedConfigMapName + "/" + configMapKey).out().trim(),
            equalTo(configMapValue)
        );
        assertThat(
            cmdKubeClient().execInPod(connectPodName, "/bin/bash", "-c", "cat external-configuration/" + dotedSecretName + "/" + secretKey).out().trim(),
            equalTo(secretPassword)
        );
    }

    @Test
    void testHostAliases() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        HostAlias hostAlias = new HostAliasBuilder()
            .withIp(aliasIp)
            .withHostnames(aliasHostname)
            .build();

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editSpec()
                .withNewTemplate()
                    .withNewPod()
                        .withHostAliases(hostAlias)
                    .endPod()
                .endTemplate()
            .endSpec()
            .build());

        String connectPodName = kubeClient().listPods(Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();

        LOGGER.info("Checking the /etc/hosts file");
        String output = cmdKubeClient().execInPod(connectPodName, "cat", "/etc/hosts").out();
        assertThat(output, containsString(etcHostsData));
    }

    @Test
    void testConfigureDeploymentStrategy() {
        KafkaResource.create(KafkaResource.kafkaEphemeral(clusterName, 3).build());

        KafkaConnectResource.create(KafkaConnectResource.kafkaConnect(clusterName, 1)
            .editSpec()
                .editOrNewTemplate()
                    .editOrNewDeployment()
                        .withDeploymentStrategy(DeploymentStrategy.RECREATE)
                    .endDeployment()
                .endTemplate()
            .endSpec()
            .build());

        String connectDepName = KafkaConnectResources.deploymentName(clusterName);

        LOGGER.info("Adding label to Connect resource, the CR should be recreated");
        KafkaConnectResource.replaceKafkaConnectResource(clusterName,
            kc -> kc.getMetadata().setLabels(Collections.singletonMap("some", "label")));
        DeploymentUtils.waitForDeploymentAndPodsReady(connectDepName, 1);

        KafkaConnect kafkaConnect = KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get();

        LOGGER.info("Checking that observed gen. is still on 1 (recreation) and new label is present");
        assertThat(kafkaConnect.getStatus().getObservedGeneration(), is(1L));
        assertThat(kafkaConnect.getMetadata().getLabels().toString(), containsString("some=label"));
        assertThat(kafkaConnect.getSpec().getTemplate().getDeployment().getDeploymentStrategy(), is(DeploymentStrategy.RECREATE));

        LOGGER.info("Changing deployment strategy to {}", DeploymentStrategy.ROLLING_UPDATE);
        KafkaConnectResource.replaceKafkaConnectResource(clusterName,
            kc -> kc.getSpec().getTemplate().getDeployment().setDeploymentStrategy(DeploymentStrategy.ROLLING_UPDATE));
        KafkaConnectUtils.waitForConnectReady(clusterName);

        LOGGER.info("Adding another label to Connect resource, pods should be rolled");
        KafkaConnectResource.replaceKafkaConnectResource(clusterName, kc -> kc.getMetadata().getLabels().put("another", "label"));
        DeploymentUtils.waitForDeploymentAndPodsReady(connectDepName, 1);

        LOGGER.info("Checking that observed gen. higher (rolling update) and label is changed");
        kafkaConnect = KafkaConnectResource.kafkaConnectClient().inNamespace(NAMESPACE).withName(clusterName).get();
        assertThat(kafkaConnect.getStatus().getObservedGeneration(), is(2L));
        assertThat(kafkaConnect.getMetadata().getLabels().toString(), containsString("another=label"));
        assertThat(kafkaConnect.getSpec().getTemplate().getDeployment().getDeploymentStrategy(), is(DeploymentStrategy.ROLLING_UPDATE));
    }

    @BeforeAll
    void setup() {
        ResourceManager.setClassResources();
        installClusterOperator(NAMESPACE, Constants.CO_OPERATION_TIMEOUT_SHORT);

        KafkaClientsResource.create(KafkaClientsResource.deployKafkaClients(false, kafkaClientsName).build());
        kafkaClientsPodName = kubeClient().listPodsByPrefixInName(kafkaClientsName).get(0).getMetadata().getName();
    }
}
