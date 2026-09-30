package com.flowpay.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.notification.AbstractIntegrationTest;
import com.flowpay.notification.entity.DeliveryStatus;
import com.flowpay.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired NotificationRepository notifications;

    @BeforeEach
    void clean() {
        reset(mailSender);
        notifications.deleteAll();
    }

    ResultActions send(String apiKey, String json) throws Exception {
        var request = post("/internal/notifications/email").contentType(MediaType.APPLICATION_JSON).content(json);
        if (apiKey != null) request.header("X-Internal-Api-Key", apiKey);
        return mockMvc.perform(request);
    }

    SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void loginOtp_isEmailedWithTheCode_andTheDeliveryIsRecordedWithoutIt() throws Exception {
        send(INTERNAL_API_KEY, """
                {"type":"LOGIN_OTP","recipient":"ada@example.com",
                 "variables":{"code":"482913","expiresInMinutes":"5"}}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENT"));

        SimpleMailMessage message = sentMessage();
        assertThat(message.getTo()).containsExactly("ada@example.com");
        assertThat(message.getSubject()).isEqualTo("Your FlowPay sign-in code");
        assertThat(message.getText()).contains("482913").contains("5 minutes");

        var record = notifications.findAll().getFirst();
        assertThat(record.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(record.toString()).doesNotContain("482913");
    }

    @Test
    void passwordReset_containsTheLink() throws Exception {
        send(INTERNAL_API_KEY, """
                {"type":"PASSWORD_RESET","recipient":"ada@example.com",
                 "variables":{"resetLink":"https://app.flowpay.test/reset?token=abc","expiresInMinutes":"30"}}""")
                .andExpect(status().isCreated());

        assertThat(sentMessage().getText()).contains("https://app.flowpay.test/reset?token=abc");
    }

    @Test
    void passwordChangedNeedsNoVariables() throws Exception {
        send(INTERNAL_API_KEY, "{\"type\":\"PASSWORD_CHANGED\",\"recipient\":\"ada@example.com\"}")
                .andExpect(status().isCreated());

        assertThat(sentMessage().getSubject()).contains("password was changed");
    }

    @Test
    void requestsWithoutTheInternalKeyAreRefused_andNothingIsSent() throws Exception {
        String body = "{\"type\":\"PASSWORD_CHANGED\",\"recipient\":\"ada@example.com\"}";

        send(null, body).andExpect(status().isUnauthorized());
        send("wrong", body).andExpect(status().isUnauthorized());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(notifications.count()).isZero();
    }

    @Test
    void missingTemplateVariableIs400() throws Exception {
        send(INTERNAL_API_KEY, "{\"type\":\"LOGIN_OTP\",\"recipient\":\"ada@example.com\",\"variables\":{}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_NOTIFICATION"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void invalidRecipientsAndHeaderInjectionAreRejected() throws Exception {
        send(INTERNAL_API_KEY, "{\"type\":\"PASSWORD_CHANGED\",\"recipient\":\"not-an-email\"}")
                .andExpect(status().isBadRequest());
        send(INTERNAL_API_KEY,
                "{\"type\":\"PASSWORD_CHANGED\",\"recipient\":\"a@example.com\\r\\nBcc: evil@example.com\"}")
                .andExpect(status().isBadRequest());
        send(INTERNAL_API_KEY, "{\"type\":\"UNKNOWN\",\"recipient\":\"a@example.com\"}")
                .andExpect(status().isBadRequest());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void whenTheMailServerFails_returns502_andTheFailureIsRecorded() throws Exception {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        send(INTERNAL_API_KEY, "{\"type\":\"PASSWORD_CHANGED\",\"recipient\":\"ada@example.com\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("DELIVERY_FAILED"));

        assertThat(notifications.findAll()).singleElement()
                .satisfies(n -> assertThat(n.getStatus()).isEqualTo(DeliveryStatus.FAILED));
    }
}
