package net.dec4234.gravel.client.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldActionsTest {
	@TempDir
	Path temp;

	@Test
	void copiesPackFolderAndReplacesOldCopy() throws Exception {
		Path pack = temp.resolve("source/test_pack");
		Files.createDirectories(pack.resolve("data/test/function"));
		Files.writeString(pack.resolve("pack.mcmeta"), "{}");
		Files.writeString(pack.resolve("data/test/function/hi.mcfunction"), "say hi");
		Path datapacks = temp.resolve("world/datapacks");
		Files.createDirectories(datapacks.resolve("test_pack"));
		Files.writeString(datapacks.resolve("test_pack/old.txt"), "old");

		assertEquals("test_pack", WorldActions.copyDataPack(pack, datapacks));

		assertEquals("say hi", Files.readString(datapacks.resolve("test_pack/data/test/function/hi.mcfunction")));
		assertTrue(Files.exists(datapacks.resolve("test_pack/pack.mcmeta")));
		assertFalse(Files.exists(datapacks.resolve("test_pack/old.txt")));
	}

	@Test
	void copiesZipPack() throws Exception {
		Path zip = temp.resolve("pack.zip");
		Files.write(zip, new byte[]{1, 2, 3});
		Path datapacks = temp.resolve("world/datapacks");

		assertEquals("pack.zip", WorldActions.copyDataPack(zip, datapacks));
		assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(datapacks.resolve("pack.zip")));
	}

	@Test
	void rejectsMissingAndBadPacks() throws Exception {
		Path datapacks = temp.resolve("datapacks");
		assertThrows(IllegalArgumentException.class, () -> WorldActions.copyDataPack(temp.resolve("nope"), datapacks));
		Path noMeta = Files.createDirectories(temp.resolve("no_meta"));
		assertThrows(IllegalArgumentException.class, () -> WorldActions.copyDataPack(noMeta, datapacks));
		Path text = Files.writeString(temp.resolve("pack.txt"), "x");
		assertThrows(IllegalArgumentException.class, () -> WorldActions.copyDataPack(text, datapacks));
	}
}
