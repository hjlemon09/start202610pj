# CMS analyzer

`execute.Execute` (the run class, `src/execute/Execute.java`) runs the existing six export steps. Each component line in
`output/page_component_tree.txt` now also contains its Controller/Renderer and JSP result.
The existing CSV format and tree structure are unchanged.

## Setup and execution

1. Apply the updated `TestController/TestController.java` to the existing Accelerator
   storefront controller and rebuild/restart that storefront. It adds the read-only
   `/cms-analyzer/components/jsp-includes` API. Keep the existing package, URL context,
   and local SQL-file path appropriate to your deployment. The sample SQL path in
   `TestController` is still the original environment-specific path.
2. Compile all analyzer sources (Java 21 or newer) from this project directory:

   ```powershell
   javac -encoding UTF-8 -cp "lib/*" -d bin src/com/*.java src/execute/*.java
   ```

3. Run from this project directory, using the Accelerator source root to search:

   ```powershell
   java "-Djdk.httpclient.allowRestrictedHeaders=host" "-Dcms.analyzer.sourceRoot=C:/hybris/bin" -cp "bin;lib/*" execute.Execute
   ```

   Preserve any existing JVM truststore/HTTPS options used in your environment.
   The default source root is `C:/hybris/bin`; it must contain the current
   Accelerator Controller sources, including relevant custom and standard sources.
   A missing directory or a directory containing no Controller Java files is an error.

To regenerate only the TXT from an existing `page_component_tree.csv`:

```powershell
java "-Djdk.httpclient.allowRestrictedHeaders=host" "-Dcms.analyzer.sourceRoot=C:/hybris/bin" -cp "bin;lib/*" execute.Execute PageComponentTextTreeExporter output
```

The JSP API defaults to
`https://localhost:9002/mmmstorefront/mmm-faec/ja/JPY/cms-analyzer/components/jsp-includes`.
Override it with `-Dcms.analyzer.jspUrl=<full URL>` if needed. This changes only
the new JSP lookup endpoint; existing page/relation export URLs are unchanged.
The API is called once per execution, only when a `JspIncludeComponent` occurs.

## Output rules

- `CMSParagraphComponent`: `Controller=なし | Renderer=cmsParagraphComponentRenderer「直書き」`
- `CMSLinkComponent`: `Controller=なし | Renderer=cmsLinkComponentRenderer「直書き」`
- `ImageMapComponent`: `Controller=なし | Renderer=imageMapComponentRenderer「直書き」`
- `AssistedServiceComponent`: `Controller=なし | Renderer=AssistedServiceComponentRenderer 「cms/asm/assistedServiceComponent.jsp(条件付き)」`
- `ProductAddToCartComponent`: `Controller=なし | Renderer=B2BAcceleratorProductAddToCartComponentRenderer 「未確認」`
- `ReorderAction` and `ApproveOrderAction`: `Controller=なし | Renderer=DefaultAddOnCMSComponentRenderer 「未確認」`
- Other types: recursively match `<componentType>Controller.java`, ignoring case.
  A match includes its source path. Multiple matches list all candidate paths with
  `MULTIPLE_MATCHES`. No match gives `DefaultCMSComponentController`.
- `JspIncludeComponent`: join the API query results by the actual component PK
  (including nested and repeated components). Output the `page` attribute as `JSP=...`.
  The server queries all JspIncludeComponent rows once rather than querying each PK separately.
- All other component types, including the seven Renderer types: `JSP=[jsp個別確認必要]`.
- Missing JSP query row: `[JSP_INCLUDE_NOT_FOUND]`; null/blank page: `[JSP_PATH_EMPTY]`.
  API/JSON errors stop the export without replacing the existing TXT.

This implements the requested filename-based judgment; it does not inspect
`getView()`, resolve Spring routing, or check JSP files on disk.
