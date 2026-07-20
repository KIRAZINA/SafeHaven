# Changelog

All notable changes to this project will be documented in this file.

## [1.0] - 2026-04-08

### Added
- **Initial Release** of SafeHaven secure file storage application
- Core encryption features:
  - AES-256-GCM symmetric encryption for files
  - PBKDF2 password-based key derivation (65,536 iterations)
  - RSA-2048 hybrid encryption for secure file sharing
- User authentication system with secure password hashing
- File management capabilities:
  - Upload and download encrypted files
  - Automatic GZIP compression before encryption
  - File search and filtering
  - Drag & drop file uploads
- Secure file sharing system using RSA key exchange
- Modern JavaFX user interface with dark theme
- Async operations with progress indicators
- Comprehensive unit test suite (28 tests)
- H2 embedded database for metadata and key storage
- FXML-based UI with CSS styling
- Properties-based configuration system

### Security Features
- Secure password handling (arrays cleared after use)
- SQL injection prevention with parameterized queries
- Secure random number generation
- Unique salt per user (16 bytes)
- Master key derivation with strong KDF
- Private key encryption with master key

### Technical Stack
- Java 17+
- JavaFX 17.0.2
- Maven 3+ build system
- H2 Database 2.1.214
- JUnit 5.9.2 with Mockito
- SLF4J logging

### Testing
- CryptoUtils cryptography tests
- AuthService authentication tests
- ShareService file sharing tests
- StorageService storage operations tests
- **Test Success Rate:** 100% (28/28 tests passing)

### Documentation
- Comprehensive README with features and usage
- Security architecture documentation
- Installation and setup instructions
- API and internal documentation

---

## [1.1] - 2026-07-20

### Security
- **P0-1: Domain-separated key derivation** — The password-derived auth verifier (stored as `USERS.password_hash`) can no longer decrypt private keys or file FEKs. PBKDF2 is now called twice with domain-separated context strings (`"safehaven-auth-v1"` and `"safehaven-mk-v1"`), producing cryptographically independent outputs. `password_hash` serves as the login verifier only; the master key is derived separately and never persisted. This is a **breaking change**: all existing users must re-register. No automatic migration is provided for the old schema.

### Fixed
- **P0-1:** `AuthService.register()` and `login()` now use `CryptoUtils.deriveAuthVerifier()` / `CryptoUtils.deriveMasterKey()` with domain-separated PBKDF2
- **P0-1:** `MainController.initData()` uses `CryptoUtils.deriveMasterKey()` instead of `CryptoUtils.generateKeyFromPassword()`
- **P0-1:** Added regression test `testPasswordHash_CannotDecryptPrivateKey()` (26 total tests)
- **P1-3:** `StorageService.deleteFile()` now wraps SHARES + FILES deletes in a single JDBC transaction with `setAutoCommit(false)` + `commit()`/`rollback()`. Physical blob deletion also included in the same transaction. Added regression test `testDeleteTransactional_RemovesSharesAndFileAtomically()`
- **P2-5:** `MainController` executor switched from `newSingleThreadExecutor()` to `newFixedThreadPool(4)` for concurrent I/O
- **P2-6:** Extracted `IAuthService`, `IStorageService`, `IShareService` interfaces. Controllers accept them via setter injection for testability
- **P2-7:** Added explicit surefire `forkCount=1` and `reuseForks=false` to prevent test pollution from static `DatabaseManager` state
- **P2-8:** Removed unused `commons-io`, `gson`, `mockito-core` dependencies from `pom.xml`. `StorageService.STORAGE_DIR` is now loaded from `application.properties` (`storage.dir`) instead of hardcoded, with `${user.home}` placeholder resolution
- **P2-9:** Replaced inline `style=` on Delete button in `main.fxml` with `styleClass="button-danger"`, with corresponding `.button-danger` CSS class in `style.css`

---

## Legend

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon to be removed features
- **Removed** - Now removed features
- **Fixed** - Bug fixes
- **Security** - Security fixes and improvements
