package com.example.ussdGateway.service;

import com.example.ussdGateway.repository.UssdServerRepository;
import com.example.ussdGateway.util.ReportResultCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class LogIngestionServiceTest {

    @Mock
    private UssdServerRepository ussdServerRepository;

    @Mock
    private IngestionChunkProcessor ingestionChunkProcessor;

    @Mock
    private ReportResultCache reportResultCache;

    @Mock
    private CacheManager cacheManager;

    private LogIngestionService service;

    @BeforeEach
    void setUp() {
        service = new LogIngestionService(ussdServerRepository, ingestionChunkProcessor, reportResultCache, cacheManager);
        ReflectionTestUtils.setField(service, "chunkSize", 5000);
        ReflectionTestUtils.setField(service, "deleteAfterIngest", true);
        ReflectionTestUtils.setField(service, "configuredBasePath", "/mediation");
    }

    @Test
    void extractsDateFromVariousFileNamingPatterns() {
        LocalDate dateStandard = ReflectionTestUtils.invokeMethod(service, "extractDateFromFileName", "TIBCO-2026-11-08.log");
        assertEquals(LocalDate.of(2026, 11, 8), dateStandard);

        LocalDate dateHourly = ReflectionTestUtils.invokeMethod(service, "extractDateFromFileName", "TIBCO-2026-11-08_10.log");
        assertEquals(LocalDate.of(2026, 11, 8), dateHourly);

        LocalDate dateHourlyDash = ReflectionTestUtils.invokeMethod(service, "extractDateFromFileName", "TIBCO-2026-11-08-14.log");
        assertEquals(LocalDate.of(2026, 11, 8), dateHourlyDash);

        LocalDate dateCompact = ReflectionTestUtils.invokeMethod(service, "extractDateFromFileName", "TIBCO-20261108.log");
        assertEquals(LocalDate.of(2026, 11, 8), dateCompact);

        LocalDate dateNoDate = ReflectionTestUtils.invokeMethod(service, "extractDateFromFileName", "ussd_current.log");
        assertNull(dateNoDate);
    }

    @Test
    void filtersOutTemporaryAndIncompleteFiles() throws Exception {
        Path tempDir = Files.createTempDirectory("log_filter_test");
        try {
            Files.createFile(tempDir.resolve("TIBCO-2026-11-08.log"));
            Files.createFile(tempDir.resolve("TIBCO-2026-11-08.log.tmp"));
            Files.createFile(tempDir.resolve("TIBCO-2026-11-08.part"));
            Files.createFile(tempDir.resolve("TIBCO-2026-11-08.writing"));
            Files.createFile(tempDir.resolve("TIBCO-2026-11-08.gz"));

            List<Path> found = ReflectionTestUtils.invokeMethod(service, "findAllLogFiles", tempDir);
            assertNotNull(found);
            assertEquals(1, found.size());
            assertEquals("TIBCO-2026-11-08.log", found.get(0).getFileName().toString());
        } finally {
            org.springframework.util.FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    @Test
    void isFileReadyForIngestionReturnsFalseForEmptyFile() throws Exception {
        Path tempFile = Files.createTempFile("empty_log", ".log");
        try {
            boolean ready = ReflectionTestUtils.invokeMethod(service, "isFileReadyForIngestion", tempFile);
            assertFalse(ready, "A 0-byte file must not be considered ready for ingestion");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
