package com.example.ussdGateway.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.ArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McnCheckerResponse {
    private boolean sipChecked;
    private boolean sipRecordsFound;
    private String sipMessage;
    @Builder.Default
    private List<UssdLogGroup> sipRecords = new ArrayList<>();

    private boolean notifierChecked;
    private boolean notifierRecordsFound;
    private String notifierMessage;
    @Builder.Default
    private List<UssdLogGroup> notifierRecords = new ArrayList<>();
}
