# DeviceTokenCrypto (ODC External Logic)

Tiny C# library for the backend of native upload (`odc/BACKEND-UPLOAD-GUIDE.md`). Needed because ODC has **no built-in** secure random or SHA-256 function that we could confirm (see the guide, "Crypto options").

| Server action | Purpose |
|---|---|
| `GenerateToken(bytes)` | `RandomNumberGenerator` -> base64url, no padding. Use `bytes = 32`. |
| `Sha256Hex(text)` | lowercase hex SHA-256 of the UTF-8 text. Store this in `Device.TokenHash`. |
| `FixedTimeEquals(a, b)` | constant-time comparison (compares the SHA-256 of both, so also length-independent). |

A SHA-256 of a 256-bit random token is sufficient (no salt/KDF needed: the token is high-entropy, not a password).

## Build

Target framework: the csproj uses `net10.0`. The OutSystems External Logic guidance (as shipped with the OutSystems ODC MCP skill, 2026) says to use .NET 10 and that ODC will refuse libraries targeting .NET 8 once its support ends; older docs/articles say .NET 6/8. If your tenant still wants `net8.0`, change that one line; the code is identical. Compile check: the code was built successfully with the .NET 9 SDK against SDK package 1.7.0 (temporarily `net9.0`); the `net10.0` target itself was not built because only the .NET 9 SDK was available when authoring.

```bash
cd odc/external-logic/DeviceTokenCrypto
dotnet publish DeviceTokenCrypto.csproj -c Release -r linux-x64 --self-contained false
cd bin/Release/net10.0/linux-x64/publish
zip -r ../DeviceTokenCrypto.zip .        # zip the CONTENTS, not the folder
```

The ZIP root must contain `DeviceTokenCrypto.dll` (plus `.deps.json` and the SDK dll). ODC limits per call: 95 s, 1 GB memory, 5.5 MB payload (irrelevant here).

## Upload

1. ODC Portal > external libraries / custom code > upload the ZIP (exact menu name **unverified**), wait for validation, then publish. The interface `Name` (`DeviceTokenCrypto`) decides new library vs new revision.
2. In ODC Studio, in the app that holds the backend: **Manage dependencies** > Server Actions > `DeviceTokenCrypto` > tick `GenerateToken`, `Sha256Hex`, `FixedTimeEquals`.

NuGet package: `OutSystems.ExternalLibraries.SDK` 1.7.0 (https://www.nuget.org/packages/OutSystems.ExternalLibraries.SDK/1.7.0 lists a `net8.0` asset with no dependencies; it works from a `net10.0` project).
