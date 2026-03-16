# SafeHaven - Secure File Storage

**SafeHaven** is a secure, local desktop application for storing files with strong encryption. Built with Java and JavaFX, it ensures your sensitive data remains private and accessible only to you.

## 🚀 Features

*   **🔒 Strong Encryption:** Files are encrypted using **AES-256 GCM** (Galois/Counter Mode) for confidentiality and integrity.
*   **🔑 Secure Authentication:** User passwords are hashed using **PBKDF2** with a unique salt per user.
*   **🤝 Secure File Sharing:** Share encrypted files safely using **RSA-2048** hybrid encryption. Your master key is never shared.
*   **📦 Compression:** Files are compressed with **GZIP** before encryption to save space.
*   **🌗 Modern UI:** Clean, responsive interface with a **Dark Theme** and tabbed views for shared files.
*   **⚡ Async Operations:** Uploads and downloads run in the background with progress indicators.
*   **🔎 Search:** Quickly filter your stored files by name.
*   **📂 Drag & Drop:** Easily upload files by dragging them into the application.

## 🛠️ Technology Stack

*   **Language:** Java 17+
*   **UI Framework:** JavaFX
*   **Build Tool:** Maven
*   **Database:** H2 Database (Embedded)
*   **Cryptography:** Java Cryptography Architecture (JCA)

## 📥 Installation & Run

### Prerequisites
*   Java Development Kit (JDK) 17 or higher.
*   Maven.

### Running with Maven
```bash
git clone https://github.com/KIRAZINA/SafeHaven.git
cd SafeHaven
mvn javafx:run
```

### Running the JAR
You can also build a self-contained JAR file:
```bash
mvn clean package
java -jar target/SafeHaven-1.0-SNAPSHOT.jar
```

## 🛡️ Security Details

1.  **Key Derivation:** When you register, a random **16-byte Salt** is generated. Your password + salt are processed via **PBKDF2WithHmacSHA256** (65,536 iterations) to derive your AES-256 master encryption key. You also generate an **RSA-2048** key pair.
2.  **Hybrid Key Wrapping:** Each file is encrypted with a unique File Encryption Key (FEK) using AES-256 GCM. The FEK is then encrypted using AES-256 GCM (with the owner's master key) or using RSA-2048 OAEP (for sharing with other users).
3.  **Local Storage:** Encrypted files are stored in your home directory under `~/.securestorage`. Metadata and wrapped keys are stored in a local H2 database (`safehaven.mv.db`).

## ⚠️ Disclaimer
SafeHaven is a demonstration of secure application principles. While it uses industry-standard algorithms, always ensure you have backups of your critical data.
