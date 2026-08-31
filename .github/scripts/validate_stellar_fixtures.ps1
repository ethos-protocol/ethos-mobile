# Validates that Stellar address test fixtures are synchronized across platforms.
# This PowerShell script is an alternative to the Python version for Windows environments.

param(
    [string]$WorkspaceRoot = (Get-Item (Split-Path $PSScriptRoot -Parent) -Parent).FullName
)

$FIXTURES_FILE = Join-Path $WorkspaceRoot "shared" "stellar-address-fixtures.json"
$ANDROID_TEST_FILE = Join-Path $WorkspaceRoot "android" "app" "src" "test" "java" "com" "ethosprotocol" "StellarAddressTest.kt"
$IOS_TEST_FILE = Join-Path $WorkspaceRoot "ios" "EthosProtocol" "Tests" "EthosProtocolTests.swift"

function Load-Fixtures {
    $json = Get-Content $FIXTURES_FILE | ConvertFrom-Json
    return $json
}

function Extract-AndroidTestAddresses {
    $content = Get-Content $ANDROID_TEST_FILE -Raw
    $validAddrs = @()
    $invalidAddrs = @()
    
    # Find all valid test addresses
    $pattern = 'assertTrue\(StellarAddress\.isValidPublicKey\(\s*"([GM][A-Z2-7]{54,68})"\s*\)\)'
    $matches = [regex]::Matches($content, $pattern)
    foreach ($match in $matches) {
        $validAddrs += $match.Groups[1].Value
    }
    
    # Find all invalid test addresses  
    $pattern = 'assertFalse\(StellarAddress\.isValidPublicKey\(\s*"([^"]*)"\s*\)\)'
    $matches = [regex]::Matches($content, $pattern)
    foreach ($match in $matches) {
        $invalidAddrs += $match.Groups[1].Value
    }
    
    return @{ Valid = $validAddrs; Invalid = $invalidAddrs }
}

function Extract-iOSTestAddresses {
    $content = Get-Content $IOS_TEST_FILE -Raw
    $validAddrs = @()
    $invalidAddrs = @()
    
    # Find all valid test addresses
    $pattern = 'XCTAssertTrue\(StellarAddress\.isValidPublicKey\((["\u2018\u2019\u201C\u201D]?)([GM][A-Z2-7]{54,68})\1\)\)'
    $matches = [regex]::Matches($content, $pattern)
    foreach ($match in $matches) {
        $validAddrs += $match.Groups[2].Value
    }
    
    # Find all invalid test addresses
    $pattern = 'XCTAssertFalse\(StellarAddress\.isValidPublicKey\((["\u2018\u2019\u201C\u201D]?)([^"]*?)\1\)\)'
    $matches = [regex]::Matches($content, $pattern)
    foreach ($match in $matches) {
        $invalidAddrs += $match.Groups[2].Value
    }
    
    return @{ Valid = $validAddrs | Select-Object -Unique; Invalid = $invalidAddrs | Select-Object -Unique }
}

function Validate-Fixtures {
    Write-Host "Validating Stellar Address Test Fixtures..."
    Write-Host "==========================================" -ForegroundColor Yellow
    
    try {
        $fixtures = Load-Fixtures
        
        # Collect expected addresses
        $expectedValid = @()
        foreach ($addr in $fixtures.valid.publicKeys) {
            $expectedValid += $addr.address
        }
        foreach ($addr in $fixtures.valid.muxedAccounts) {
            $expectedValid += $addr.address
        }
        
        $expectedInvalid = @()
        foreach ($addr in $fixtures.invalid) {
            $expectedInvalid += $addr.address
        }
        
        Write-Host "Expected valid fixtures: $($expectedValid.Count)"
        Write-Host "Expected invalid fixtures: $($expectedInvalid.Count)"
        
        # Extract from test files
        $android = Extract-AndroidTestAddresses
        $ios = Extract-iOSTestAddresses
        
        Write-Host ""
        Write-Host "Android valid fixtures found: $($android.Valid.Count)"
        Write-Host "Android invalid fixtures found: $($android.Invalid.Count)"
        Write-Host "iOS valid fixtures found: $($ios.Valid.Count)"
        Write-Host "iOS invalid fixtures found: $($ios.Invalid.Count)"
        
        # Check for missing fixtures
        $errors = @()
        
        $missingAndroidValid = @($expectedValid | Where-Object { $_ -notin $android.Valid })
        if ($missingAndroidValid.Count -gt 0) {
            $errors += "❌ Android missing valid fixtures: $($missingAndroidValid -join ', ')"
        }
        
        $missingiOSValid = @($expectedValid | Where-Object { $_ -notin $ios.Valid })
        if ($missingiOSValid.Count -gt 0) {
            $errors += "❌ iOS missing valid fixtures: $($missingiOSValid -join ', ')"
        }
        
        $missingAndroidInvalid = @($expectedInvalid | Where-Object { $_ -notin $android.Invalid })
        if ($missingAndroidInvalid.Count -gt 0) {
            $errors += "❌ Android missing invalid fixtures: $($missingAndroidInvalid -join ', ')"
        }
        
        $missingiOSInvalid = @($expectedInvalid | Where-Object { $_ -notin $ios.Invalid })
        if ($missingiOSInvalid.Count -gt 0) {
            $errors += "❌ iOS missing invalid fixtures: $($missingiOSInvalid -join ', ')"
        }
        
        if ($errors.Count -gt 0) {
            Write-Host ""
            Write-Host "Validation Failed" -ForegroundColor Red
            foreach ($error in $errors) {
                Write-Host $error
            }
            return $false
        }
        
        Write-Host ""
        Write-Host "✅ Stellar Address Test Fixtures Valid" -ForegroundColor Green
        return $true
        
    } catch {
        Write-Host "❌ Error: $_" -ForegroundColor Red
        return $false
    }
}

$success = Validate-Fixtures
exit $(if ($success) { 0 } else { 1 })
