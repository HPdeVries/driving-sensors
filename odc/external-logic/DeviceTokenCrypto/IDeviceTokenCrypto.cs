using OutSystems.ExternalLibraries.SDK;

namespace FlexLogic.DeviceTokenCrypto
{
    [OSInterface(
        Name = "DeviceTokenCrypto",
        Description = "Secure random token generation, SHA-256 hashing and constant-time comparison for per-device tokens."
    )]
    public interface IDeviceTokenCrypto
    {
        [OSAction(Description = "Cryptographically secure random token, base64url without padding (32 bytes -> 43 chars).",
                  ReturnName = "Token", ReturnDescription = "The token. Show it to the device once; store only its hash.")]
        string GenerateToken(
            [OSParameter(Description = "Number of random bytes, 16 to 128. Use 32.")] int bytes);

        [OSAction(Description = "SHA-256 of the UTF-8 text as lowercase hex (64 chars).",
                  ReturnName = "Hash", ReturnDescription = "Lowercase hex digest.")]
        string Sha256Hex(
            [OSParameter(Description = "Text to hash")] string text);

        [OSAction(Description = "Constant-time equality of two texts (UTF-8). False when either is empty.",
                  ReturnName = "Equal", ReturnDescription = "True when equal.")]
        bool FixedTimeEquals(
            [OSParameter(Description = "First text")] string a,
            [OSParameter(Description = "Second text")] string b);
    }
}
