/*
 * SPDX-FileCopyrightText: Copyright (c) 2016-2025 Objectionary.com
 * SPDX-License-Identifier: MIT
 */
package org.eolang.maven;

import com.github.lombrozo.xnav.Filter;
import com.github.lombrozo.xnav.Xnav;
import com.jcabi.log.Logger;
import com.jcabi.xml.XML;
import com.jcabi.xml.XMLDocument;
import com.yegor256.xsline.Shift;
import com.yegor256.xsline.StClasspath;
import com.yegor256.xsline.TrClasspath;
import com.yegor256.xsline.TrDefault;
import com.yegor256.xsline.TrJoined;
import com.yegor256.xsline.Train;
import com.yegor256.xsline.Xsline;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.cactoos.func.StickyFunc;
import org.cactoos.io.InputOf;
import org.cactoos.text.Joined;
import org.eolang.parser.TrFull;

/**
 * Transpile.
 *
 * @since 0.1
 * @checkstyle ClassFanOutComplexityCheck (400 lines)
 */
@Mojo(
    name = "transpile",
    defaultPhase = LifecyclePhase.PROCESS_SOURCES,
    threadSafe = true,
    requiresDependencyResolution = ResolutionScope.COMPILE
)
@SuppressWarnings("PMD.LongVariable")
public final class TranspileMojo extends SafeMojo {
    /**
     * The directory where to transpile to.
     */
    static final String DIR = "8-transpile";

    /**
     * Cache directory for transpiled sources.
     */
    private static final String CACHE = "transpiled";

    /**
     * Java extension.
     */
    private static final String JAVA = "java";

    /**
     * The directory where to put pre-transpile files.
     */
    private static final String PRE = "7-pre";

    /**
     * Parsing train with XSLs.
     */
    private static final Train<Shift> TRAIN = new TrFull(
        new TrJoined<>(
            new TrClasspath<>(
                "/org/eolang/maven/transpile/classes.xsl",
                "/org/eolang/maven/transpile/tests.xsl",
                "/org/eolang/maven/transpile/anonymous-to-nested.xsl",
                "/org/eolang/maven/transpile/package.xsl",
                "/org/eolang/maven/transpile/attrs.xsl",
                "/org/eolang/maven/transpile/data.xsl"
            ).back(),
            new TrDefault<>(
                new StClasspath(
                    "/org/eolang/maven/transpile/to-java.xsl",
                    String.format("disclaimer %s", new Disclaimer())
                )
            )
        )
    );

    /**
     * Target directory.
     * @checkstyle MemberNameCheck (7 lines)
     */
    @Parameter(
        property = "eo.generatedDir",
        required = true,
        defaultValue = "${project.build.directory}/generated-sources"
    )
    private File generatedDir;

    /**
     * Output.
     * @checkstyle MemberNameCheck (7 lines)
     */
    @Parameter(
        property = "eo.outputDir",
        required = true,
        defaultValue = "${project.build.outputDirectory}"
    )
    private File outputDir;

    /**
     * Add to source root.
     *
     * @checkstyle MemberNameCheck (7 lines)
     */
    @Parameter(property = "eo.addSourcesRoot")
    @SuppressWarnings("PMD.ImmutableField")
    private boolean addSourcesRoot = true;

    /**
     * Add to test source root.
     *
     * @checkstyle MemberNameCheck (7 lines)
     */
    @Parameter(property = "eo.addTestSourcesRoot")
    private boolean addTestSourcesRoot;

    @Override
    public void exec() {
        final Collection<TjForeign> sources = this.scopedTojos().withShaken();
        final Function<XML, XML> transform = this.transpilation();
        final int saved = new Threaded<>(
            sources,
            tojo -> this.transpiled(tojo, transform)
        ).total();
        Logger.info(
            this, "Transpiled %d XMIRs, created %d Java files in %[file]s",
            sources.size(), saved, this.generatedDir
        );
        if (this.addSourcesRoot) {
            this.project.addCompileSourceRoot(this.generatedDir.getAbsolutePath());
            Logger.info(
                this, "The directory added to Maven 'compile-source-root': %[file]s",
                this.generatedDir
            );
        }
        if (this.addTestSourcesRoot) {
            this.project.addTestCompileSourceRoot(this.generatedDir.getAbsolutePath());
            Logger.info(
                this, "The directory added to Maven 'test-compile-source-root': %[file]s",
                this.generatedDir
            );
        }
    }

    /**
     * Transpile.
     * @param tojo Tojo that should be transpiled.
     * @param transform Optimization that transpiles
     * @return Number of transpiled files.
     * @throws java.io.IOException If any issues with I/O
     */
    private int transpiled(
        final TjForeign tojo,
        final Function<XML, XML> transform
    ) throws IOException {
        final Path source = tojo.shaken();
        final XML xmir = new XMLDocument(source);
        final Path base = this.targetDir.toPath().resolve(TranspileMojo.DIR);
        final Path target = new Place(new ProgramName(xmir).get()).make(base, AssembleMojo.XMIR);
        final Supplier<String> hsh = new TojoHash(tojo);
        final AtomicBoolean rewrite = new AtomicBoolean(false);
        new FpFork(
            (src, tgt) -> new Xnav(xmir.inner())
                .path("/program/objects/o[1]/o[@name='λ']")
                .findAny()
                .isEmpty(),
            new FpDefault(
                src -> {
                    rewrite.compareAndSet(false, true);
                    return transform.apply(xmir).toString();
                },
                this.cache.toPath().resolve(TranspileMojo.CACHE),
                this.plugin.getVersion(),
                hsh,
                base.relativize(target)
            ),
            new FpIgnore()
        ).apply(source, target);
        return this.javaGenerated(rewrite.get(), target, hsh.get());
    }

    /**
     * Transpile XSL transformations.
     * If {@link SafeMojo#trackTransformationSteps} is {@code true} - we create new {@link Xsline}
     * for every XMIR in purpose of thread safety.
     * @return XSL transformations that transpiles XMIR to Java.
     */
    private Function<XML, XML> transpilation() {
        final Train<Shift> measured = this.measured(TranspileMojo.TRAIN);
        final Function<XML, XML> func;
        if (this.trackTransformationSteps) {
            func = xml -> new Xsline(
                new TrSpy(
                    measured,
                    new StickyFunc<>(
                        new ProgramPlace(this.targetDir.toPath().resolve(TranspileMojo.PRE))
                    )
                )
            ).pass(xml);
        } else {
            func = new Xsline(measured)::pass;
        }
        return func;
    }

    /**
     * Generate java files and count them.
     * @param rewrite Rewrite .java files even if they exist
     * @param target Full target path to XMIR after transpilation optimizations
     * @param hsh Tojo hash
     * @return Amount of generated .java files
     * @throws IOException If fails to save files
     */
    private int javaGenerated(
        final boolean rewrite,
        final Path target,
        final String hsh
    ) throws IOException {
        final AtomicInteger saved = new AtomicInteger(0);
        if (Files.exists(target)) {
            final Collection<Xnav> classes = new Xnav(target)
                .element("program")
                .element("objects")
                .elements(Filter.withName("class"))
                .collect(Collectors.toList());
            for (final Xnav clazz : classes) {
                final String jname = clazz.attribute("java-name").text().get();
                final Path tgt = new Place(jname).make(
                    this.generatedDir.toPath(), TranspileMojo.JAVA
                );
                this.pinfo(tgt, jname, clazz.attribute("package").text().orElse(""));
                final Supplier<Path> che = new CachePath(
                    this.cache.toPath().resolve(TranspileMojo.CACHE),
                    this.plugin.getVersion(),
                    hsh,
                    this.generatedDir.toPath().relativize(tgt)
                );
                final Footprint generated = new FpGenerated(
                    src -> {
                        saved.incrementAndGet();
                        Logger.debug(
                            this, "Generated %[file]s (%[size]s) file from %[file]s (%[size]s)",
                            tgt, tgt.toFile().length(), target, target.toFile().length()
                        );
                        return new InputOf(
                            new Joined(
                                "",
                                clazz.elements(Filter.withName("java")).map(
                                    java -> java.text().orElse("")
                                ).collect(Collectors.toList())
                            )
                        );
                    }
                );
                final Footprint both = new FpUpdateBoth(generated, che);
                new FpIfReleased(
                    this.plugin.getVersion(),
                    hsh,
                    new FpFork(
                        (src, trgt) -> {
                            if (rewrite) {
                                Logger.debug(
                                    this,
                                    "Rewriting %[file]s because XMIR %[file]s was changed",
                                    trgt,
                                    target
                                );
                            }
                            return rewrite;
                        },
                        both,
                        new FpIfTargetExists(
                            new FpIgnore(),
                            new FpIfTargetExists(
                                trgt -> che.get(),
                                new FpUpdateFromCache(che),
                                both
                            )
                        )
                    ),
                    generated
                ).apply(Paths.get(""), tgt);
            }
        }
        return saved.get();
    }

    /**
     * Save {@code package-info.java} next to Java file.
     *
     * <p>Each .java file that we create must have a corresponding
     * {@code package-info.java} file. The presence of this file enables
     * us to use {@code XmirPackage} annotation, passing information about
     * EO objects from XMIR to Java runtime.</p>
     *
     * @param java Full path to .java file
     * @param oname Java object name (e.g. "EOorg.EOeolang.EOio.EOstdio")
     * @param pname Package name (e.g. "org.eolang.io")
     * @throws IOException If fails to save file
     */
    private void pinfo(final Path java, final String oname, final String pname)
        throws IOException {
        final Path pinfo = java.getParent().resolve("package-info.java");
        if (!pinfo.toFile().exists() && !pname.isEmpty()) {
            if (pinfo.getParent().toFile().mkdirs()) {
                Logger.debug(this, "Directory created for %[file]s", pinfo);
            }
            String pkg = oname;
            if (oname.contains(".")) {
                pkg = pkg.substring(0, pkg.lastIndexOf('.'));
            }
            Files.write(
                pinfo,
                String.join(
                    "\n",
                    "/**",
                    " * This file was auto-generated by eo-maven-plugin,",
                    " * don't modify it, all changes will be lost anyway.",
                    " */",
                    String.format("// @org.eolang.XmirPackage(\"%s\")", pname),
                    String.format("package %s;", pkg)
                ).getBytes(StandardCharsets.UTF_8)
            );
            Logger.debug(this, "Saved %[file]s (%[size]s)", pinfo, pinfo.toFile().length());
        }
    }
}
