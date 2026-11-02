FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS base

FROM --platform=$BUILDPLATFORM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
COPY . .
RUN dotnet publish DeviceTokenCrypto.UnitTests/DeviceTokenCrypto.UnitTests.csproj -c Release -o /app/publish

FROM base AS final
WORKDIR /app
COPY --from=build /app/publish .
ENTRYPOINT ["dotnet", "DeviceTokenCrypto.UnitTests.dll"]
