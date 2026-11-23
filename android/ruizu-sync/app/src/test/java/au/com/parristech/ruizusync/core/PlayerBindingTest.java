package au.com.parristech.ruizusync.core;

import au.com.parristech.ruizusync.core.PlayerBinding.Restore;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The invariant under test: a folder remembered for one player is never reused for a different
 * player, while a single-player install keeps working unchanged.
 *
 * <p>Both RUIZU units report the same SAF volume {@code 0000-006F}, which is why the volume alone
 * may never be treated as device identity.
 */
public final class PlayerBindingTest {

    private static final String VOLUME = "0000-006F";
    private static final String SYLVIE = "11111111-1111-1111-1111-111111111111";
    private static final String JOSH = "22222222-2222-2222-2222-222222222222";

    @Test public void deviceIdKeyWinsOverVolume() {
        assertEquals("device:" + SYLVIE, PlayerBinding.storeKey(SYLVIE, VOLUME));
        assertTrue(PlayerBinding.isDeviceKey(PlayerBinding.storeKey(SYLVIE, VOLUME)));
    }

    @Test public void volumeKeyIsTheFallbackForAnUnidentifiedPlayer() {
        assertEquals(VOLUME, PlayerBinding.storeKey(null, VOLUME));
        assertEquals(VOLUME, PlayerBinding.storeKey("   ", VOLUME));
        assertFalse(PlayerBinding.isDeviceKey(PlayerBinding.storeKey(null, VOLUME)));
    }

    @Test public void noKeyWhenNothingIsKnown() {
        assertNull(PlayerBinding.storeKey(null, null));
        assertNull(PlayerBinding.storeKey("", ""));
    }

    @Test public void sameDeviceRestoresTheRememberedFolder() {
        assertEquals(Restore.MATCH, PlayerBinding.decide(false, SYLVIE, SYLVIE));
        assertTrue(PlayerBinding.allowsAutomaticReuse(Restore.MATCH));
    }

    @Test public void differentDeviceIsRefused() {
        // The whole defect: Josh's unit on Sylvie's volume must not inherit Sylvie's folder.
        assertEquals(Restore.REFUSE_DIFFERENT_DEVICE, PlayerBinding.decide(false, SYLVIE, JOSH));
        assertFalse(PlayerBinding.allowsAutomaticReuse(Restore.REFUSE_DIFFERENT_DEVICE));
    }

    @Test public void identifiedPlayerWithItsOwnEntryRestoresEvenWhenTheVolumeEntryDiffers() {
        assertEquals(Restore.MATCH, PlayerBinding.decide(true, JOSH, SYLVIE));
        assertTrue(PlayerBinding.allowsAutomaticReuse(Restore.MATCH));
    }

    @Test public void recordedIdentityWithNoReadableMarkerIsRefused() {
        assertEquals(Restore.REFUSE_UNVERIFIED, PlayerBinding.decide(false, SYLVIE, null));
        assertEquals(Restore.REFUSE_UNVERIFIED, PlayerBinding.decide(false, SYLVIE, "  "));
        assertFalse(PlayerBinding.allowsAutomaticReuse(Restore.REFUSE_UNVERIFIED));
    }

    @Test public void legacyInstallKeepsOriginalBehaviour() {
        // Nothing about identity was ever recorded: the single-player behaviour must not change.
        assertEquals(Restore.LEGACY, PlayerBinding.decide(false, null, null));
        assertEquals(Restore.LEGACY, PlayerBinding.decide(false, "", ""));
        assertTrue(PlayerBinding.allowsAutomaticReuse(Restore.LEGACY));
    }

    @Test public void aPlayerGainingAMarkerLaterStillMatchesItsLegacyEntry() {
        // The old install wrote no device id; the first launch after the upgrade creates a marker.
        // Until that binding is stored, the recorded id is absent, so the legacy path applies.
        assertEquals(Restore.LEGACY, PlayerBinding.decide(false, null, JOSH));
    }

    @Test public void ownEntryNeedsAnIdentityToBeUseful() {
        // An "own entry" can only exist for an identified player; without an id it must not
        // short-circuit the checks.
        assertEquals(Restore.LEGACY, PlayerBinding.decide(true, null, null));
        assertEquals(Restore.REFUSE_UNVERIFIED, PlayerBinding.decide(true, SYLVIE, null));
    }

    @Test public void idsAreComparedAfterTrimming() {
        assertEquals(Restore.MATCH, PlayerBinding.decide(false, " " + SYLVIE + " ", SYLVIE));
    }
}
