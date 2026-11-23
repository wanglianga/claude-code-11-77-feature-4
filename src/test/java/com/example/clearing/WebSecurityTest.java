package com.example.clearing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 安全边界：未认证 401；viewer 只读；admin 可写；API 文档开放 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSecurityTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void unauthenticatedGets401() {
        ResponseEntity<String> response = rest.getForEntity("/api/tasks", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void viewerCanReadButNotWrite() {
        TestRestTemplate viewer = rest.withBasicAuth("viewer", "viewer123");
        assertEquals(HttpStatus.OK, viewer.getForEntity("/api/buckets", String.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                viewer.postForEntity("/api/scheduler/tick", null, String.class).getStatusCode());
    }

    @Test
    void adminCanReadAndWrite() {
        TestRestTemplate admin = rest.withBasicAuth("admin", "admin123");
        assertEquals(HttpStatus.OK, admin.getForEntity("/api/demo/overview", String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                admin.postForEntity("/api/scheduler/tick", null, String.class).getStatusCode());
    }

    @Test
    void openApiDocsArePublic() {
        assertEquals(HttpStatus.OK, rest.getForEntity("/v3/api-docs", String.class).getStatusCode());
    }
}
