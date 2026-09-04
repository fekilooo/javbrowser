# Source development

Implement `JavSource` under `nativeapp/source`, then register it in `NativeMainActivity`.

1. Declare only working capabilities.
2. Return provider-neutral models; never expose HTML.
3. Preserve original ID/URL in `SourceRef`.
4. Prefer OkHttp/Jsoup; use `HeadlessWebEngine` only for required JavaScript.
5. Map challenges to `VerificationRequired`; never bypass CAPTCHAs.
6. Attach required Referer/User-Agent headers to playback variants.
7. Add sanitized fixture tests; keep normal tests independent of live sites.

```kotlin
class ExampleSource : JavSource {
    override val id = "example"
    override val displayName = "Example"
    override val capabilities = setOf(SourceCapability.SEARCH)
    override suspend fun search(query: String, page: Int, filters: List<SourceFilterValue>) =
        SourceResult.Success(emptyList<JavSearchResult>())
    override suspend fun getDetails(ref: SourceRef) =
        SourceResult.Failure(SourceError.Unsupported("No details"))
}
```

This only demonstrates shape; an empty source must not be registered or claimed as supported.
