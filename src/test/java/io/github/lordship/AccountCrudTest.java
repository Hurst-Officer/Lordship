package io.github.lordship;

import io.github.lordship.access.AgentLoginRequest;
import io.github.lordship.accounts.AccountService;
import io.github.lordship.accounts.AccountStatus;
import io.github.lordship.accounts.internal.AccountCreationRequest;
import io.github.lordship.accounts.internal.AccountUpdateRequest;
import io.github.lordship.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@Transactional
public class AccountCrudTest extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AccountService accountService;

    @MockitoBean
    AuditService auditService;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String loginAsRoot() throws Exception {
        AgentLoginRequest loginRequest = new AgentLoginRequest(rootEmail, rootPassword);

        MvcResult result = mockMvc.perform(post("/api/agents/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asString();
    }


    private UUID getAutoCreatedAccountId(UUID tenancyId) {
        return accountService.getAccountByTenancyId(tenancyId).orElseThrow().uuid();
    }

    // -------------------------------------------------------------------------
    // Tests: unauthorized access returns 401
    // -------------------------------------------------------------------------

    @Test
    void unauthorizedCreateReturns401() throws Exception {
        AccountCreationRequest request = new AccountCreationRequest(UUID.randomUUID(), null);

        mockMvc.perform(post("/accounts/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedGetByIdReturns401() throws Exception {
        mockMvc.perform(get("/accounts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedGetByPropertyReturns401() throws Exception {
        mockMvc.perform(get("/accounts/property/TST01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedUpdateReturns401() throws Exception {
        AccountUpdateRequest request = new AccountUpdateRequest(AccountStatus.ACTIVE, false, null);

        mockMvc.perform(put("/accounts/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedDeleteReturns401() throws Exception {
        mockMvc.perform(delete("/accounts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Tests: authorized requests return correct responses
    // -------------------------------------------------------------------------

    @Test
    void authorizedGetAutoCreatedAccountReturns200() throws Exception {
        String token = loginAsRoot();
        UUID tenancyId = testData.insertChainToTenancy().uuid();
        UUID accountId = getAutoCreatedAccountId(tenancyId);

        mockMvc.perform(get("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.tenancyId").value(tenancyId.toString()))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.balanceCached").value(0))
                .andExpect(jsonPath("$.autopayEnabled").value(false));
    }

    @Test
    void authorizedGetByIdReturns200() throws Exception {
        String token = loginAsRoot();
        UUID tenancyId = testData.insertChainToTenancy().uuid();
        System.out.println(tenancyId);
        String accountId = getAutoCreatedAccountId(tenancyId).toString();

        mockMvc.perform(get("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
    }

    @Test
    void authorizedUpdateReturns200() throws Exception {
        String token = loginAsRoot();
        UUID tenancyId = testData.insertChainToTenancy().uuid();
        System.out.println("TENANCY ID:  " + tenancyId);
        String accountId = getAutoCreatedAccountId(tenancyId).toString();

        AccountUpdateRequest updateRequest = new AccountUpdateRequest(
                AccountStatus.DELINQUENT,
                true,
                "Tenant missed payment"
        );

        mockMvc.perform(put("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("DELINQUENT"))
                .andExpect(jsonPath("$.balanceCached").value(0))
                .andExpect(jsonPath("$.autopayEnabled").value(true))
                .andExpect(jsonPath("$.notes").value("Tenant missed payment"));
    }

    @Test
    void authorizedDeleteReturns204() throws Exception {
        String token = loginAsRoot();
        UUID tenancyId = testData.insertChainToTenancy().uuid();
        String accountId = getAutoCreatedAccountId(tenancyId).toString();

        // soft delete
        mockMvc.perform(delete("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // confirm it's gone
        mockMvc.perform(get("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
