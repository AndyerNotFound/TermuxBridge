src/ was the original layout (package dir = repo root).
Since v1.7.0 the Gradle build (app/src/main/java) is the source of truth;
src/ is kept in sync by tools/sync-src.sh and exists only so the legacy
zero-dep pipeline and syntax check keep working. Do not edit src/ directly.
