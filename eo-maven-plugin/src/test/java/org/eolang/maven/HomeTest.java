/*
 * SPDX-FileCopyrightText: Copyright (c) 2016-2025 Objectionary.com
 * SPDX-License-Identifier: MIT
 */
package org.eolang.maven;

import com.yegor256.Mktmp;
import com.yegor256.MktmpResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.cactoos.text.Randomized;
import org.cactoos.text.TextOf;
import org.cactoos.text.UncheckedText;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test for {@link Home}.
 *
 * @since 0.22
 */
@ExtendWith(MktmpResolver.class)
final class HomeTest {

    @ValueSource(ints = {0, 100, 1_000, 10_000})
    @ParameterizedTest
    void saves(final int size, @Mktmp final Path temp) throws IOException {
        final Path resolve = Paths.get("1.txt");
        final String content = new UncheckedText(new Randomized(size)).asString();
        new Home(temp).save(content, resolve);
        MatcherAssert.assertThat(
            "The saved file contents are not the same as expected",
            new UncheckedText(new TextOf(temp.resolve(resolve))).asString(),
            Matchers.is(content)
        );
    }

    @Test
    void exists(@Mktmp final Path temp) throws IOException {
        final Path path = Paths.get("file.txt");
        Files.write(temp.resolve(path), "any content".getBytes());
        MatcherAssert.assertThat(
            "The file should exist, but it doesn't",
            new Home(temp).exists(path),
            Matchers.is(true)
        );
    }

    @Test
    void existsInDir(@Mktmp final Path temp) throws IOException {
        final Path target = temp.resolve("dir/subdir/file.txt");
        target.getParent().toFile().mkdirs();
        Files.write(target, "any content".getBytes());
        MatcherAssert.assertThat(
            "The file in the subdirectory must exist, but it doesn't",
            new Home(temp.resolve("dir")).exists(Paths.get("subdir/file.txt")),
            Matchers.is(true)
        );
    }

    @Test
    void existsInDirDifferentEncryption(@Mktmp final Path temp) throws IOException {
        final String filename = "文件名.txt";
        final byte[] bytes = filename.getBytes(StandardCharsets.UTF_16BE);
        final String decoded = new String(bytes, StandardCharsets.UTF_16BE);
        final Path directory = temp.resolve("directory");
        new Home(directory).save("any content", Paths.get(decoded));
        MatcherAssert.assertThat(
            "The file with a different name encoding must exist, but it doesn't",
            new Home(directory).exists(Paths.get(filename)),
            Matchers.is(true)
        );
    }

    @Test
    void existsInDirWithSpecialSymbols(@Mktmp final Path temp) throws IOException {
        final String filename = "EOorg/EOeolang/EOmath/EOnan$EOas_int$EO@";
        final byte[] bytes = filename.getBytes("CP1252");
        final String decoded = new String(bytes, "CP1252");
        final Path directory = temp.resolve("directory");
        new Home(directory).save("any content", Paths.get(decoded));
        MatcherAssert.assertThat(
            "The file with special characters in the name must exist, but it doesn't",
            new Home(directory).exists(Paths.get(filename)),
            Matchers.is(true)
        );
    }

    @Test
    void loadsBytesFromExistingFile(@Mktmp final Path temp) throws IOException {
        final Home home = new Home(temp);
        final String content = "bar";
        final Path subfolder = Paths.get("subfolder", "foo.txt");
        home.save(content, subfolder);
        MatcherAssert.assertThat(
            "The uploaded file content does not match the expected one",
            new TextOf(home.load(subfolder)),
            Matchers.equalTo(new TextOf(content))
        );
    }

    @Test
    void loadsFromAbsentFile(@Mktmp final Path temp) {
        Assertions.assertThrows(
            NoSuchFileException.class,
            () -> new Home(temp).load(Paths.get("nonexistent")),
            "A NoSuchFileException was expected when uploading a missing file"
        );
    }

    @Test
    void throwsExceptionOnAbsolute(@Mktmp final Path temp) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new Home(temp).exists(temp.toAbsolutePath()),
            "IllegalArgumentException was expected when passing an absolute path"
        );
    }
}
