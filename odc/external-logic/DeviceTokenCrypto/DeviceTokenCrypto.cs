using System.Security.Cryptography;
using System.Text;

namespace FlexLogic.DeviceTokenCrypto
{
    public class DeviceTokenCrypto : IDeviceTokenCrypto
    {
        public string GenerateToken(int bytes)
        {
            if (bytes < 16 || bytes > 128)
                throw new ArgumentOutOfRangeException(nameof(bytes), "bytes must be between 16 and 128");
            var buf = RandomNumberGenerator.GetBytes(bytes);
            return Convert.ToBase64String(buf).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        }

        public string Sha256Hex(string text)
        {
            var hash = SHA256.HashData(Encoding.UTF8.GetBytes(text ?? string.Empty));
            return Convert.ToHexString(hash).ToLowerInvariant();
        }

        public bool FixedTimeEquals(string a, string b)
        {
            if (string.IsNullOrEmpty(a) || string.IsNullOrEmpty(b)) return false;
            // Hash both first so the comparison time does not depend on the length either.
            var ha = SHA256.HashData(Encoding.UTF8.GetBytes(a));
            var hb = SHA256.HashData(Encoding.UTF8.GetBytes(b));
            return CryptographicOperations.FixedTimeEquals(ha, hb);
        }
    }
}
