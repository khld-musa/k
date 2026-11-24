package com.example.ussdGateway.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SshConnectRequest {
    @Size(max = 255)
    private String host;

    @Min(1) @Max(65535)
    private int port = 22;

    @Size(max = 100)
    private String username;

    private String password;

    @Min(10) @Max(500)
    private int cols = 80;

    @Min(5) @Max(200)
    private int rows = 24;

    private Long savedSessionId;
    private String privateKey;
    private String passphrase;
}
