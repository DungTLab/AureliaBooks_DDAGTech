package com.ddagtech.aureliabooks.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;

/** Explicitly opt-in, offline draft writer. It cannot construct an S3 client. */
@EnabledIfSystemProperty(named = "r2.smoke.prepare", matches = "true")
class R2SmokeDraftPreparationTest {
    @Test void writeOfflineApprovalDraft() throws Exception {
        R2SmokeProtocol.prepare(Path.of(System.getProperty("r2.smoke.prepare-dir")),
                System.getProperty("r2.smoke.source-revision"));
    }
}
