package de.turing85.quarkus.artemis.xa;

import com.google.common.truth.Truth;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.apache.activemq.artemis.jms.client.ActiveMQTopic;
import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Objects;

@QuarkusTest
class JmsRouteTest {
  public static final String TEST_MESSAGE = "test";

  @Inject
  CamelContext context;

  @Inject
  @Default
  @SuppressWarnings("CdiInjectionPointsInspection")
  ConnectionFactory firstConnectionFactory;

  @Inject
  @Identifier("second")
  @SuppressWarnings("CdiInjectionPointsInspection")
  ConnectionFactory secondConnectionFactory;

  @EndpointInject("mock:mock")
  MockEndpoint mockEndpoint;

  @BeforeEach
  void setup() {
    mockEndpoint.reset();
  }

  @Nested
  @DisplayName("First Route -> 9 consumers -> all good")
  class FirstJmsRouteTest {
    @BeforeEach
    void setup() throws Exception {
      stopRoute(FirstJmsRoute.ID);

      emptyTopic(FirstJmsRoute.TOPIC, FirstJmsRoute.SUBSCRIPTION_NAME, firstConnectionFactory);

      startRoute(FirstJmsRoute.ID);
    }

    @Test
    void happyTest() throws Exception {
      // GIVEN
      mockEndpoint.expectedMessageCount(1);
      AdviceWith.adviceWith(
          context,
          FirstJmsRoute.ID,
          d -> d.weaveAddLast().to(mockEndpoint).id("mockFirst"));

      // WHEN
      sendMessageToTopic(FirstJmsRoute.TOPIC, firstConnectionFactory);

      // THEN
      mockEndpoint.assertIsSatisfied();
      Truth
          .assertThat(noMessageOnTopic(
              FirstJmsRoute.TOPIC,
              FirstJmsRoute.SUBSCRIPTION_NAME,
              firstConnectionFactory))
          .isTrue();

      // CLEANUP
      AdviceWith.adviceWith(
          context,
          FirstJmsRoute.ID,
          d -> d.weaveById("mockFirst").remove());
    }

    @Test
    void rollbackTest() throws Exception {
      // GIVEN
      AdviceWith.adviceWith(
          context,
          FirstJmsRoute.ID,
          d -> d.weaveAddFirst().throwException(
              Exception.class,
              "Artificial exception to test rollback"));

      // WHEN
      sendMessageToTopic(FirstJmsRoute.TOPIC, firstConnectionFactory);

      // THEN
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> routeIsStopped(FirstJmsRoute.ID));
      Truth
          .assertThat(testMessageOnTopic(
              FirstJmsRoute.TOPIC,
              FirstJmsRoute.SUBSCRIPTION_NAME,
              firstConnectionFactory))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("Second Route -> 10 consumers -> rollback fails")
  class SecondJmsRouteTest {
    @BeforeEach
    void setup() throws Exception {
      stopRoute(SecondJmsRoute.ID);

      emptyTopic(SecondJmsRoute.TOPIC, SecondJmsRoute.SUBSCRIPTION_NAME, secondConnectionFactory);

      startRoute(SecondJmsRoute.ID);
    }

    @Test
    void happyTest() throws Exception {
      // GIVEN
      mockEndpoint.expectedMessageCount(1);
      AdviceWith.adviceWith(
          context,
          SecondJmsRoute.ID,
          d -> d.weaveAddLast().to(mockEndpoint).id("mockSecond"));

      // WHEN
      sendMessageToTopic(SecondJmsRoute.TOPIC, secondConnectionFactory);

      // THEN
      mockEndpoint.assertIsSatisfied();
      Truth
          .assertThat(noMessageOnTopic(
              SecondJmsRoute.TOPIC,
              SecondJmsRoute.SUBSCRIPTION_NAME,
              secondConnectionFactory))
          .isTrue();

      // CLEANUP
      AdviceWith.adviceWith(
          context,
          SecondJmsRoute.ID,
          d -> d.weaveById("mockSecond").remove());
    }

    @Test
    void rollbackTest() throws Exception {
      // GIVEN
      AdviceWith.adviceWith(
          context,
          SecondJmsRoute.ID,
          d -> d.weaveAddFirst().throwException(
              Exception.class,
              "Artificial exception to test rollback"));

      // WHEN
      sendMessageToTopic(SecondJmsRoute.TOPIC, secondConnectionFactory);

      // THEN
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> routeIsStopped(SecondJmsRoute.ID));
      Truth
          .assertThat(testMessageOnTopic(
              SecondJmsRoute.TOPIC,
              SecondJmsRoute.SUBSCRIPTION_NAME,
              secondConnectionFactory))
          .isTrue();
    }
  }

  void sendMessageToTopic(String topicName, ConnectionFactory connectionFactory) {
    try (JMSContext context = connectionFactory.createContext(1)) {
      context.createProducer().send(new ActiveMQTopic(topicName), TEST_MESSAGE);
    }
  }

  boolean noMessageOnTopic(
      String topicName,
      String subscriptionName,
      ConnectionFactory connectionFactory) {
    try (
        JMSContext context = connectionFactory.createContext(1);
        JMSConsumer consumer = context.createSharedDurableConsumer(
            new ActiveMQTopic(topicName),
            subscriptionName)) {
      return Objects.isNull(consumer.receive(Duration.ofSeconds(1).toMillis()));
    }
  }

  boolean testMessageOnTopic(
      String topicName,
      String subscriptionName,
      ConnectionFactory connectionFactory) {
    try (
        JMSContext context = connectionFactory.createContext(1);
        JMSConsumer consumer = context.createSharedDurableConsumer(
            new ActiveMQTopic(topicName),
            subscriptionName)) {
      Message received = consumer.receive(Duration.ofSeconds(1).toMillis());
      if (Objects.nonNull(received)) {
        try {
          return received.getBody(String.class).equals(TEST_MESSAGE);
        } catch (JMSException e) {
          return false;
        }
      } else {
        return false;
      }
    }
  }

  boolean routeIsStarted(String routeId) {
    return context.getRouteController().getRouteStatus(routeId).isStarted();
  }

  boolean routeIsStarting(String routeId) {
    return context.getRouteController().getRouteStatus(routeId).isStarting();
  }

  boolean routeIsStopped(String routeId) {
    return context.getRouteController().getRouteStatus(routeId).isStopped();
  }

  boolean routeIsStopping(String routeId) {
    return context.getRouteController().getRouteStatus(routeId).isStopping();
  }

  void stopRoute(String routeId) throws Exception {
    if (!routeIsStopping(routeId) && !routeIsStopped(routeId)) {
      context.getRouteController().stopRoute(routeId);
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> routeIsStopped(routeId));
    }
  }

  void emptyTopic(
      String topicName,
      String subscriptionName,
      ConnectionFactory connectionFactory) throws JMSException {
    try (
        JMSContext context = connectionFactory.createContext(1);
        JMSConsumer consumer = context.createSharedDurableConsumer(
            new ActiveMQTopic(topicName),
            subscriptionName)) {
      Message message;
      while ((message = consumer.receive(Duration.ofSeconds(1).toMillis())) != null) {
        message.acknowledge();
      }
    }
  }

  void startRoute(String routeId) throws Exception {
    if (!routeIsStarting(routeId) && !routeIsStopped(routeId)) {
      context.getRouteController().startRoute(routeId);
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> routeIsStarted(routeId));
    }
  }
}