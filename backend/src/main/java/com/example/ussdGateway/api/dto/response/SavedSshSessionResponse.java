package com.example.ussdGateway.api.dto.response;

import com.example.ussdGateway.entity.SavedSshSession;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SavedSshSessionResponse {
    private Long id;
    private String name;
    private String host;
    private Integer port;
    private String username;
    private boolean hasPassword;
    private boolean hasPrivateKey;

    public static SavedSshSessionResponse fromEntity(SavedSshSession entity) {
        return SavedSshSessionResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .hasPassword(entity.getEncryptedPassword() != null && !entity.getEncryptedPassword().isEmpty())
                .hasPrivateKey(entity.getPrivateKey() != null && !entity.getPrivateKey().isEmpty())
                .build();
    }
}
