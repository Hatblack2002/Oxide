plugins { id("java-library") }
group = "com.teahack.oxide"
version = "1.0.0"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit(); testLogging { events("passed", "skipped", "failed") } }
