using Xunit;

namespace FlexLogic.DeviceTokenCrypto.UnitTests
{
    public class DeviceTokenCryptoTests
    {
        private readonly IDeviceTokenCrypto _sut = new DeviceTokenCrypto();

        [Fact]
        public void GenerateToken_32Bytes_Is43CharBase64Url()
        {
            var t = _sut.GenerateToken(32);
            Assert.Equal(43, t.Length);
            Assert.Matches("^[A-Za-z0-9_-]+$", t);
        }

        [Fact]
        public void GenerateToken_IsRandom()
        {
            var seen = new HashSet<string>();
            for (var i = 0; i < 1000; i++) Assert.True(seen.Add(_sut.GenerateToken(32)));
        }

        [Theory]
        [InlineData(15)]
        [InlineData(129)]
        public void GenerateToken_RejectsOutOfRange(int bytes)
        {
            Assert.Throws<ArgumentOutOfRangeException>(() => _sut.GenerateToken(bytes));
        }

        [Fact]
        public void Sha256Hex_KnownVector()
        {
            Assert.Equal("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", _sut.Sha256Hex("abc"));
            Assert.Equal("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", _sut.Sha256Hex(""));
        }

        [Fact]
        public void FixedTimeEquals_Works()
        {
            Assert.True(_sut.FixedTimeEquals("abc", "abc"));
            Assert.False(_sut.FixedTimeEquals("abc", "abd"));
            Assert.False(_sut.FixedTimeEquals("abc", "abcd"));
            Assert.False(_sut.FixedTimeEquals("", ""));
        }
    }
}
