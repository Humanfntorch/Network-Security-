import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.logging.Logger;

public class Server
{
    // RSA pub/priv key pair for server
    private PrivateKey privateKey;
    private PublicKey publicKey;
    // Server's certificate
    private Certificate serverCert;
    // Client's public RSA Key
    private PublicKey clientKey;
    // Client's certificate
    private Certificate clientCert;
    // Encryption Cipher using client's public RSA key
    private Cipher encRSACipher;
    // Decryption Cipher using server's private RSA key
    private Cipher decRSACipher;
    // Server's port for tcp connection
    private final int SERVERPORT = 8080;
    // Logger for error handling
    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());
    // aes key generated from premaster secret (used for enc and dec)
    private SecretKey aesKey;
    // Hmac key generated from premaster secret (used for Integrity protection)
    private SecretKey hmacKey;

    public Server()
    {
        // Load the server's certificate from a JKS file
        try
        {
            KeyStore keystore = KeyStore.getInstance("JKS");
            keystore.load(new FileInputStream("serverkeystore.jks"),
                    "serverPassword".toCharArray());

            // Get private RSA key
            this.privateKey =
                    (PrivateKey) keystore.getKey("serverKey", "serverPassword".toCharArray());
            // Get the certificate
            this.serverCert = keystore.getCertificate("serverKey");
            // Get public RSA key
            this.publicKey = serverCert.getPublicKey();

            // Get the certificate chain
            Certificate[] certChain = keystore.getCertificateChain("serverKey");
        }
        catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException
                | UnrecoverableKeyException e)
        {
            e.printStackTrace();
        }

        // Initialize encryption cipher using private key
        try
        {
            this.encRSACipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.encRSACipher.init(Cipher.ENCRYPT_MODE, this.privateKey);
        }
        catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e)
        {
            e.printStackTrace();
        }

        // print certificate by field values
        printCertContents(this.serverCert);
    }

    /*
     * Accepts an object argument and calculates the total size of the object in bytes using a
     * ByteArrayOutputStream and ObjectOutputStream. Object out stream serializes the object, writes
     * it to the Byte array stream and returns the total size in bytes written to the Byte Array
     * Stream. If Object serialization/writing to stream fails, returns -1, else: size of object as
     * type byte
     */
    public static long getObjectSize(Object obj)
    {
        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // oos writes to the baos
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            // Serializes object using oos and writes to baos
            oos.writeObject(obj);
            oos.close();
            // Convert int to byte (hopefully no truncation) and return
            return (long) baos.size();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
    }

    /*
     * Generates an AES key from the premaster secret that was computed during the SSL handshake
     * phase. premasterSecret is forced to a fix-sized number of bytes (256) using SHA256 to compute
     * a message digest. The bytes of the premaster secret (updated through SHA256) are then used to
     * exponentiate a generated AES key's bytes to form a new set of bytes that are encoded to the
     * AES key
     */
    public static SecretKey generateAESKey(byte[] premasterSecret) throws NoSuchAlgorithmException
    {
        // Use PBKDF2 to derive an AES key from the shared secret
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec =
                new PBEKeySpec(new String(premasterSecret, StandardCharsets.UTF_8).toCharArray(),
                        "salt".getBytes(), 65536, 256);
        SecretKey tmp;
        try
        {
            tmp = factory.generateSecret(spec);
            SecretKey aesKey = new SecretKeySpec(tmp.getEncoded(), "AES");
            return aesKey;
        }
        catch (InvalidKeySpecException e)
        {
            e.printStackTrace();
        }
        return null;
    }

    /*
     * Generates an HMAC key using the premaster secret formed during the SSL handshake. Premaster
     * secret is passed through SHA-256 to fix the byte size to a predefined limit (256) and is used
     * to generate the HMAC secret key.
     */
    public static SecretKey generateHMACKey(byte[] premasterSecret) throws NoSuchAlgorithmException
    {
        // Use SHA-256 to derive a fixed-length key from the premaster secret
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(premasterSecret);

        // Create a new HMAC key from the derived key bytes
        SecretKey hmacKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        return hmacKey;
    }

    /*
     * Prints the meaningful contents of the given Certificate argument. Ensures certificate is a
     * valid x509Certificate and prints the following fields to the console: Subject Distinguished
     * Name (DN) Issuer Distinguished Name (DN) Serial Number Validity date (date of notBefore thru
     * date of notAfter) Signature algorithm used Public key algorithm used Certificate version
     * Number
     */
    public static void printCertContents(Certificate certificate)
    {
        if (certificate instanceof X509Certificate)
        {
            X509Certificate x509Cert = (X509Certificate) certificate;
            System.out.println("Subject: " + x509Cert.getSubjectDN());
            System.out.println("Issuer: " + x509Cert.getIssuerDN());
            System.out.println("Serial number: " + x509Cert.getSerialNumber());
            System.out.println(
                    "Validity: " + x509Cert.getNotBefore() + " to " + x509Cert.getNotAfter());
            System.out.println("Signature algorithm: " + x509Cert.getSigAlgName());
            System.out.println("Public key algorithm: " + x509Cert.getPublicKey().getAlgorithm());
            System.out.println("Version: " + x509Cert.getVersion());
        }
    }

    /*
     * Validates the given receivedCert argument (on server side, so certificate originator is
     * expected to be Client). Validation of certificate depends on: Integrity protection of the
     * certificate: Matches the signature of the certificate (used by Client's private RSA key) to
     * the public RSA key. Certificate Issuer: Expected certificate issuer should be Client,
     * distinguished name is parsed to find the certificate issuer name (CN) and ensures match
     * between expected "Client" Certificate validity date: Certificate's issuance date is ensured
     * to valid by analyzing certificate's not before date and not after date relative to the
     * current date of this function's invocation. Assuming certificate is validated, client's
     * public RSA key and certificate is cached. Otherwise: System report terminating error and
     * terminates.
     */
    public void validateCertificate(Certificate receivedCert)
    {
        // cast certificate for easy manipulation
        receivedCert = (X509Certificate) receivedCert;
        System.out.println("Received certificate has given contents: ");
        printCertContents(receivedCert);
        System.out.println();

        // Ensure proper signature and integrity protection of certificate
        System.out.println("Validating signature of certificate using embedded public key.");
        try
        {
            receivedCert.verify(receivedCert.getPublicKey());
            System.out.println("Certificate has confirmed signature and has been untampered with.");
        }
        catch (InvalidKeyException | CertificateException | NoSuchAlgorithmException
                | NoSuchProviderException | SignatureException e)
        {
            e.printStackTrace();
        }
        System.out.println();

        // Validate CN of certificate
        System.out.println(
                "Expecting certificate issuer name to match name: \"Client\", validating CN on certificate. ");

        String subjectDN = ((X509Certificate) receivedCert).getSubjectDN().getName();
        String[] subjectFields = subjectDN.split(",");
        String cnField = null;
        for (String field : subjectFields)
        {
            if (field.trim().startsWith("CN="))
            {
                cnField = field.trim();
                break;
            }
        }
        if (cnField != null)
        {
            String cnValue = cnField.substring(3); // extract the value after "CN="
            System.out.println("CN of certificate found as: " + cnValue);
            if (cnValue.equals("Client"))
            {
                System.out.println("Issuer of certificate authenticated.");
            }
            else
            {
                System.out.println(
                        "Issuer of certificate does not match expected issuer. Terminating session.");
                // REPLACE WITH FAILED HANDSHAKE
                System.exit(0);
            }
        }
        else
        {
            System.out.println(
                    "CN field not found in the subject distinguished name. Terminating session.");
            // REPLACE WITH FAILED HANDSHAKE
            System.exit(0);
        }
        System.out.println();

        // Ensure validity of certificate issuance date.
        // before date
        String certBeforeDate = ((X509Certificate) receivedCert).getNotBefore().toString();
        // not after date
        String certAfterDate = ((X509Certificate) receivedCert).getNotAfter().toString();
        SimpleDateFormat format = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US);
        try
        {
            // Current date to validate certificate date issuance
            Date currentDate = new Date();
            System.out.println("Current date: " + currentDate.toString());
            // certificate's not before date
            Date beforeDate = format.parse(certBeforeDate);
            System.out.println(
                    "Certificate is valid after the following date: " + beforeDate.toString());
            // certificate validity must have a before date after current date
            if (currentDate.after(beforeDate))
            {
                // certificate's not after date
                Date afterDate = format.parse(certAfterDate);
                System.out.println(
                        "Certificate invalid after the following date: " + afterDate.toString());
                // certificate validity must have a not after date before the current date
                if (currentDate.before(afterDate))
                {
                    System.out.println(
                            "Certificate issuance data has been validated as current and acceptable.");
                }
                else
                {
                    System.out.println(
                            "Certificate issuance date indicates certificate expired. Terminating session.");
                    // REPLACE WITH FAILED HANDSHAKE
                    System.exit(0);
                }
            }
            else
            {
                System.out.println(
                        "Certificate before validity date is after current date. Terminating session.");
                // REPLACE WITH FAILED HANDSHAKE
                System.exit(0);
            }

        }
        catch (ParseException e)
        {
            e.printStackTrace();
        }
        System.out.println();
        System.out.println("Client certificate has been validated successfully.");
        // cache certificate and key
        this.clientCert = receivedCert;
        this.clientKey = this.clientCert.getPublicKey();
        // initialize decryption cipher using client key
        try
        {
            this.decRSACipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            this.decRSACipher.init(Cipher.DECRYPT_MODE, this.clientKey);
        }
        catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e)
        {
            e.printStackTrace();
        }
    }

    /*
     * SSL handshake protocol
     */
    public void sslHandshakeSuccess()
    {
        // List contains all sent/received messages for MD computation
        ArrayList<Object> mdList = new ArrayList<Object>();

        // Setup server
        try
        {
            // Open server
            ServerSocket serverSocket = new ServerSocket(SERVERPORT);
            // Indicates when a client is still communicating with server
            boolean openSocket = true;

            while (openSocket)
            {
                System.out.println("Server: Waiting for connection...");
                // Accept in client and report to console
                Socket client = serverSocket.accept();
                System.out.println("Server: Connection has been established");
                // in/out streams for communication with client
                ObjectOutputStream outStream =
                        new ObjectOutputStream(client.getOutputStream());
                ObjectInputStream inStream =
                        new ObjectInputStream(client.getInputStream());
                System.out.println();

                // MESSAGE 1
                System.out.println("Message 1 (SSL3_MT_CLIENT_HELLO): ");
                // Read in Message 1
                try
                {
                    // Header received first
                    SSLRecordHeader m1Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 1 header received. Header contents:");
                    m1Header.printHeader();
                    // Cipher suite string received next
                    String m1String = (String) inStream.readObject();
                    System.out.println("Message 1 Cipher suite received. Cipher suite contents:");
                    System.out.println(m1String);
                    // Write all m1 messages to mdList
                    mdList.add(m1Header);
                    mdList.add(m1String);
                }
                catch (ClassNotFoundException e)
                {
                    e.printStackTrace();
                }
                System.out.println();

                // MESSAGE 2
                System.out.println();
                System.out.println("Message 2 (SSL3_MT_SERVER_HELLO): ");
                // Send server encryption and I/G algo suite (enforced)
                String m2String = "Cipher Suite Accepted";
                System.out.println("Server: Cipher suite being sent: " + m2String);

                // find byte size of cipher suite
                long m2StringSize = getObjectSize(m2String);
                System.out.println("Server: size of cipher suite message: " + m2StringSize);
                // Size of second message being sent to client
                long[] m2ContentLength = {m2StringSize};

                // initial Server Hello header.
                SSLRecordHeader m2Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_SERVER_HELLO, m2ContentLength);
                // Transmit header and payload to client
                outStream.writeObject(m2Header);
                outStream.writeObject(m2String);
                outStream.flush();
                // Add m2Header and m2String to mdList
                mdList.add(m2Header);
                mdList.add(m2String);

                // MESSAGE 3
                System.out.println();
                System.out.println("Message 3 (SSL3_MT_CERTIFICATE): ");
                // find byte size of server's certificate
                long m3ServerCertSize = getObjectSize(this.serverCert);
                System.out
                        .println("Server: size of certificate being sent: " + m3ServerCertSize);
                System.out.println();
                long[] m3ContentLength = {m3ServerCertSize};
                SSLRecordHeader m3Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_CERTIFICATE, m3ContentLength);
                // Transmit header and payload to client
                outStream.writeObject(m3Header);
                outStream.writeObject(this.serverCert);
                outStream.flush();
                // Add m3Header and server cert to mdList
                mdList.add(m3Header);
                mdList.add(this.serverCert);

                // MESSAGE 4
                System.out.println();
                System.out.println("Message 4 (SSL3_MT_CERTIFICATE_REQUEST): ");
                String m4String = "Please respond with certificate.";
                System.out.println("Server: Response request being sent: " + m4String);

                long m4StringSize = getObjectSize(m4String);
                System.out.println("Server: Size of request message: " + m4StringSize);
                long[] m4ContentLength = {m4StringSize};
                SSLRecordHeader m4Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_CERTIFICATE_REQUEST, m4ContentLength);
                // Transmit header and payload to client
                outStream.writeObject(m4Header);
                outStream.writeObject(m4String);
                outStream.flush();
                // Add m4Header and m4String to mdList
                mdList.add(m4Header);
                mdList.add(m4String);

                // MESSAGE 5
                System.out.println();
                System.out.println("Message 5 (SSL3_MT_CERTIFICATE): ");
                // Receive cert etc
                Certificate m5Certificate = null;
                SSLRecordHeader m5Header = null;
                try
                {
                    // Header received first
                    m5Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 5 header received. Header contents:");
                    m3Header.printHeader();

                    // Certificate received next
                    m5Certificate = (Certificate) inStream.readObject();
                    System.out.println(
                            "Message 5 Client certificate received. client certificate contents:");
                    printCertContents(m5Certificate);
                    // Add m5Header and m5Certificate to mdList
                    mdList.add(m5Header);
                    mdList.add(m5Certificate);
                }
                catch (ClassNotFoundException e)
                {
                    e.printStackTrace();
                }
                System.out.println();

                // VALIDATE CERTIFICATE
                System.out.println();
                System.out.println("Server: Validating received certificate: ");
                validateCertificate(m5Certificate);

                // MESSAGE 6
                System.out.println();
                System.out.println("Message 6 (SSL3_MT_SERVER_KEY_EXCHANGE): ");
                // generate random nonce:
                SecureRandom random = new SecureRandom();
                // 8 bits in 1 byte, therefore 8 bytes = 64 bit challenge
                byte[] m6Nonce = new byte[8];
                random.nextBytes(m6Nonce);
                System.out.println(
                        "Server: Nonce generated: " + ByteBuffer.wrap(m6Nonce).getLong());
                try
                {
                    // Encrypt nonce using RSA key
                    m6Nonce = this.encRSACipher.doFinal(m6Nonce);
                    System.out.println(
                            "Server: Encrypted Nonce being sent to client: "
                                    + ByteBuffer.wrap(m6Nonce).getLong());
                }
                catch (IllegalBlockSizeException | BadPaddingException e)
                {
                    e.printStackTrace();
                }

                // Message 6 nonce header
                long m6NonceSize = getObjectSize(m6Nonce);
                System.out.println("Server: size of encrypted nonce being sent: " + m6NonceSize);
                long[] m6ContentLength = {m6NonceSize};
                SSLRecordHeader m6Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_SERVER_KEY_EXCHANGE, m6ContentLength);

                // Transmit header and payload to server
                outStream.writeObject(m6Header);
                outStream.writeObject(m6Nonce);
                outStream.flush();
                // Add all m6 messages to mdList
                mdList.add(m6Header);
                mdList.add(m6Nonce);

                // MESSAGE 7
                System.out.println();
                System.out.println("Message 7 (SSL3_MT_CLIENT_KEY_EXCHANGE): ");
                // rec nonce
                byte[] m7Nonce = null;
                SSLRecordHeader m7Header = null;
                try
                {
                    m7Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 7 header received. Header contents:");
                    m7Header.printHeader();
                    // Rec nonce
                    m7Nonce = (byte[]) inStream.readObject();
                    // add messages to mdlist
                    mdList.add(m7Header);
                    mdList.add(m7Nonce);

                    System.out.println("Message 7 Nonce received. Encrypted nonce contents:");
                    System.out.println(ByteBuffer.wrap(m7Nonce).getLong());

                    // Decrypt nonce:
                    m7Nonce = this.decRSACipher.doFinal(m7Nonce);
                    System.out.println("Message 7 Decrypted nonce contents:");
                    System.out.println(ByteBuffer.wrap(m7Nonce).getLong());
                }
                catch (ClassNotFoundException | IllegalBlockSizeException | BadPaddingException e)
                {
                    e.printStackTrace();
                }


                // MESSAGE 8 (PREMASTER SECRET)
                System.out.println();
                System.out.println("Message 8 (SSL3_MT_CLIENT_KEY_EXCHANGE): ");
                byte[] preMasterSecretSave = null;
                SSLRecordHeader m8Header = null;
                try
                {
                    m8Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 8 header received. Header contents:");
                    m8Header.printHeader();
                    // Read in premaster secret
                    preMasterSecretSave = (byte[]) inStream.readObject();
                    byte[] preMasterSecret = preMasterSecretSave.clone();
                    // add secret for MD computation
                    mdList.add(m8Header);
                    mdList.add(preMasterSecret);

                    System.out.println(
                            "Message 8 preMaster secret received. Encrypted secret contents:");
                    System.out.println(ByteBuffer.wrap(preMasterSecret).getLong());

                    // Decrypt nonce:
                    preMasterSecret = this.decRSACipher.doFinal(preMasterSecret);
                    System.out.println("Message 8 Decrypted preMaster contents:");
                    System.out.println(ByteBuffer.wrap(preMasterSecret).getLong());

                    // Generate AES and HMAC keys using preMaster secret
                    this.aesKey = generateAESKey(preMasterSecret);
                    this.hmacKey = generateHMACKey(preMasterSecret);
                }
                catch (ClassNotFoundException | IllegalBlockSizeException | BadPaddingException
                        | NoSuchAlgorithmException e)
                {
                    e.printStackTrace();
                }

                // MESSAGE DIGEST COMP
                // Create a list to store all computed MD
                System.out.println();
                ArrayList<byte[]> byteList = new ArrayList<byte[]>();
                try
                {
                    String id = "SERVER";
                    byte[] key = new byte[preMasterSecretSave.length + id.getBytes().length];
                    // Set the key for the hash
                    SecretKeySpec secretKeySpec = new SecretKeySpec(key, "HmacSHA1");
                    // Initialize the Mac with the secret key
                    Mac mac = Mac.getInstance("HmacSHA1");
                    mac.init(secretKeySpec);
                    // Baos for serializing objects in mdList
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();

                    for (Object o : mdList)
                    {
                        // Serialize object
                        ObjectOutputStream oos = new ObjectOutputStream(baos);
                        // write serialized obj to baos
                        oos.writeObject(o);
                        oos.flush();
                        byte[] serialized = baos.toByteArray();
                        baos.reset();
                        byte[] hash = mac.doFinal(serialized);
                        byteList.add(hash);
                    }
                }
                catch (NoSuchAlgorithmException | InvalidKeyException e)
                {
                    e.printStackTrace();
                }
                // Print out message digest
                System.out.println();
                System.out.println("Message digest computed using keyed SHA-1: ");
                for (byte[] byteArray : byteList)
                {
                    System.out.println(Arrays.toString(byteArray));
                }
                System.out.println();

                // MESSAGE 9
                System.out.println();
                System.out.println("Message 9 (SSL3_MT_FINISHED): ");
                // Create header for mac
                long m9MacSize = getObjectSize(byteList);
                System.out.println("Server: size of mac being sent: " + m9MacSize);
                long[] m9ContentLength = {m9MacSize};
                SSLRecordHeader m9Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_FINISHED, m9ContentLength);

                // Transmit mac to client
                outStream.writeObject(m9Header);
                outStream.writeObject(byteList);

                // Compute expected Client MD
                // Create a list to store all computed MD
                ArrayList<byte[]> byteList2 = new ArrayList<byte[]>();
                String id = "CLIENT";
                byte[] key = new byte[preMasterSecretSave.length + id.getBytes().length];
                // Set the key for the hash
                SecretKeySpec secretKeySpec = new SecretKeySpec(key, "HmacSHA1");

                // Initialize the Mac with the secret key
                Mac mac;
                try
                {
                    mac = Mac.getInstance("HmacSHA1");
                    mac.init(secretKeySpec);
                    // Baos for serializing objects in mdList
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();

                    for (Object o : mdList)
                    {
                        // Serialize object
                        ObjectOutputStream oos = new ObjectOutputStream(baos);
                        // write serialized obj to baos
                        oos.writeObject(o);
                        oos.flush();
                        byte[] serialized = baos.toByteArray();
                        baos.reset();
                        byte[] hash = mac.doFinal(serialized);
                        byteList2.add(hash);
                    }
                }
                catch (NoSuchAlgorithmException | InvalidKeyException e)
                {
                    e.printStackTrace();
                }

                // MESSAGE 10
                System.out.println();
                System.out.println("Message 10 (SSL3_MT_FINISHED): ");
                // Receive mac from server
                try
                {
                    SSLRecordHeader m10Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 10 header received. Header contents:");
                    m10Header.printHeader();

                    System.out.println("Server: Client's MD received. Validating now.");
                    ArrayList<byte[]> serverMd = (ArrayList<byte[]>) inStream.readObject();

                    // Compare received md with expected results
                    if (compareArrayLists(serverMd, byteList2))
                    {
                        System.out
                                .println("Server: client's MD has been validated. Thanks client!");
                    }
                    else
                    {
                        System.out.println("server: client's MD has been compromise. Abort!");
                        System.exit(0);
                    }

                }
                catch (ClassNotFoundException e)
                {
                    e.printStackTrace();
                }

                System.out.println();
                // HANDSHAKE PROTOCOL FINISHED

                // Begin data transfer protocol:
                System.out.println("Server: Beginning data transfer protocol");
                // Open the file and read its contents into a byte array
                byte[] fileBytes = Files.readAllBytes(Paths.get("test.txt"));

                // encrypt file array with aes key:
                Cipher cipher;
                byte[] hmacSig = null;
                try
                {
                    cipher = Cipher.getInstance("AES");
                    cipher.init(Cipher.ENCRYPT_MODE, this.aesKey);
                    fileBytes = cipher.doFinal(fileBytes);

                    // Sign file with hmac key
                    mac = Mac.getInstance("HmacSHA256");
                    mac.init(this.hmacKey);
                    mac.update(fileBytes);
                    hmacSig = mac.doFinal();
                }
                catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                        | IllegalBlockSizeException | BadPaddingException e)
                {
                    e.printStackTrace();
                }

                // DATA TRANSFER MESSAGE
                System.out.println();
                System.out.println("Data Transfer (SSL3_RT_APPLICATION_DATA): ");
                // Create header for file being transferred
                long m11FileSize = getObjectSize(fileBytes);
                System.out.println("Server: size of file being sent: " + m11FileSize);
                long[] m11ContentLength = {m11FileSize};
                SSLRecordHeader m11Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_RT_APPLICATION_DATA, m11ContentLength);

                // Transmit header and file to client
                outStream.writeObject(m11Header);
                outStream.write(fileBytes);
                outStream.flush();
                System.out.println();

                // DATA TRANSFER MESSAGE
                System.out.println();
                System.out.println("Data Transfer continued (SSL3_RT_APPLICATION_DATA): ");
                // Create header for hmac being transferred
                long m12HmacSize = getObjectSize(hmacSig);
                System.out.println("Server: size of file being sent: " + m12HmacSize);
                long[] m12ContentLength = {m12HmacSize};
                SSLRecordHeader m12Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_RT_APPLICATION_DATA, m12ContentLength);

                // Transmit header and hmac
                outStream.writeObject(m12Header);
                outStream.writeObject(hmacSig);
                outStream.flush();

                // Servers done. Close shop
                openSocket = false;
                // close client socket and in/out streams
                client.close();
                inStream.close();
                outStream.close();
            }
            // close server socket
            serverSocket.close();
        }
        catch (UnknownHostException ex)
        {
            LOGGER.log(java.util.logging.Level.SEVERE, null, ex);
        }
        catch (IOException e)
        {
            LOGGER.log(java.util.logging.Level.SEVERE, null, e);
        }
    }


    /*
     * SSL handshake protocol, that is rigged to fail
     */
    public void sslHandshakeFail()
    {
        // List contains all sent/received messages for MD computation
        ArrayList<Object> mdList = new ArrayList<Object>();

        // Setup server
        try
        {
            // Open server
            ServerSocket serverSocket = new ServerSocket(SERVERPORT);
            // Indicates when a client is still communicating with server
            boolean openSocket = true;

            while (openSocket)
            {
                System.out.println("Server: Waiting for connection...");
                // Accept in client and report to console
                Socket client = serverSocket.accept();
                System.out.println("Server: Connection has been established");
                // in/out streams for communication with client
                ObjectOutputStream outStream =
                        new ObjectOutputStream(client.getOutputStream());
                ObjectInputStream inStream =
                        new ObjectInputStream(client.getInputStream());
                System.out.println();

                // MESSAGE 1
                System.out.println("Message 1 (SSL3_MT_CLIENT_HELLO): ");
                // Read in Message 1
                try
                {
                    // Header received first
                    SSLRecordHeader m1Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 1 header received. Header contents:");
                    m1Header.printHeader();
                    // Cipher suite string received next
                    String m1String = (String) inStream.readObject();
                    System.out.println("Message 1 Cipher suite received. Cipher suite contents:");
                    System.out.println(m1String);
                    // Write all m1 messages to mdList
                    mdList.add(m1Header);
                    mdList.add(m1String);
                }
                catch (ClassNotFoundException e)
                {
                    e.printStackTrace();
                }
                System.out.println();

                // MESSAGE 2
                System.out.println();
                System.out.println("Message 2 (SSL3_MT_SERVER_HELLO): ");
                // Send server encryption and I/G algo suite (enforced)
                String m2String = "Cipher Suite Accepted";
                System.out.println("Server: Cipher suite being sent: " + m2String);

                // find byte size of cipher suite
                long m2StringSize = getObjectSize(m2String);
                System.out.println("Server: size of cipher suite message: " + m2StringSize);
                // Size of second message being sent to client
                long[] m2ContentLength = {m2StringSize};

                // initial Server Hello header.
                SSLRecordHeader m2Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_SERVER_HELLO, m2ContentLength);
                // Transmit header and payload to client
                outStream.writeObject(m2Header);
                outStream.writeObject(m2String);
                outStream.flush();
                // Add m2Header and m2String to mdList
                mdList.add(m2Header);
                mdList.add(m2String);

                // MESSAGE 3
                System.out.println();
                System.out.println("Message 3 (SSL3_MT_CERTIFICATE): ");
                // find byte size of server's certificate
                long m3ServerCertSize = getObjectSize(this.serverCert);
                System.out
                        .println("Server: size of certificate being sent: " + m3ServerCertSize);
                System.out.println();
                long[] m3ContentLength = {m3ServerCertSize};
                SSLRecordHeader m3Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_CERTIFICATE, m3ContentLength);
                // Transmit header and payload to client
                outStream.writeObject(m3Header);
                outStream.writeObject(this.serverCert);
                outStream.flush();
                // Add m3Header and server cert to mdList
                mdList.add(m3Header);
                mdList.add(this.serverCert);

                // MESSAGE 4
                System.out.println();
                System.out.println("Message 4 (SSL3_MT_CERTIFICATE_REQUEST): ");
                String m4String = "Please respond with certificate.";
                System.out.println("Server: Response request being sent: " + m4String);

                long m4StringSize = getObjectSize(m4String);
                System.out.println("Server: Size of request message: " + m4StringSize);
                long[] m4ContentLength = {m4StringSize};
                SSLRecordHeader m4Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_CERTIFICATE_REQUEST, m4ContentLength);
                // Transmit header and payload to client
                outStream.writeObject(m4Header);
                outStream.writeObject(m4String);
                outStream.flush();
                // Add m4Header and m4String to mdList
                mdList.add(m4Header);
                mdList.add(m4String);

                // MESSAGE 5
                System.out.println();
                System.out.println("Message 5 (SSL3_MT_CERTIFICATE): ");
                // Receive cert etc
                Certificate m5Certificate = null;
                SSLRecordHeader m5Header = null;
                try
                {
                    // Header received first
                    m5Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 5 header received. Header contents:");
                    m3Header.printHeader();

                    // Certificate received next
                    m5Certificate = (Certificate) inStream.readObject();
                    System.out.println(
                            "Message 5 Client certificate received. client certificate contents:");
                    printCertContents(m5Certificate);
                    // Add m5Header and m5Certificate to mdList
                    mdList.add(m5Header);
                    mdList.add(m5Certificate);
                }
                catch (ClassNotFoundException e)
                {
                    e.printStackTrace();
                }
                System.out.println();

                // VALIDATE CERTIFICATE
                System.out.println();
                System.out.println("Server: Validating received certificate: ");
                validateCertificate(m5Certificate);

                // MESSAGE 6
                System.out.println();
                System.out.println("Message 6 (SSL3_MT_SERVER_KEY_EXCHANGE): ");
                // generate random nonce:
                SecureRandom random = new SecureRandom();
                // 8 bits in 1 byte, therefore 8 bytes = 64 bit challenge
                byte[] m6Nonce = new byte[8];
                random.nextBytes(m6Nonce);
                System.out.println(
                        "Server: Nonce generated: " + ByteBuffer.wrap(m6Nonce).getLong());
                try
                {
                    // Encrypt nonce using RSA key
                    m6Nonce = this.encRSACipher.doFinal(m6Nonce);
                    System.out.println(
                            "Server: Encrypted Nonce being sent to client: "
                                    + ByteBuffer.wrap(m6Nonce).getLong());
                }
                catch (IllegalBlockSizeException | BadPaddingException e)
                {
                    e.printStackTrace();
                }

                // Message 6 nonce header
                long m6NonceSize = getObjectSize(m6Nonce);
                System.out.println("Server: size of encrypted nonce being sent: " + m6NonceSize);
                long[] m6ContentLength = {m6NonceSize};
                SSLRecordHeader m6Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_SERVER_KEY_EXCHANGE, m6ContentLength);

                // Transmit header and payload to server
                outStream.writeObject(m6Header);
                outStream.writeObject(m6Nonce);
                outStream.flush();
                // Add all m6 messages to mdList
                mdList.add(m6Header);
                mdList.add(m6Nonce);

                // MESSAGE 7
                System.out.println();
                System.out.println("Message 7 (SSL3_MT_CLIENT_KEY_EXCHANGE): ");
                // rec nonce
                byte[] m7Nonce = null;
                SSLRecordHeader m7Header = null;
                try
                {
                    m7Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 7 header received. Header contents:");
                    m7Header.printHeader();
                    // Rec nonce
                    m7Nonce = (byte[]) inStream.readObject();
                    // add messages to mdlist
                    mdList.add(m7Header);
                    mdList.add(m7Nonce);

                    System.out.println("Message 7 Nonce received. Encrypted nonce contents:");
                    System.out.println(ByteBuffer.wrap(m7Nonce).getLong());

                    // Decrypt nonce:
                    m7Nonce = this.decRSACipher.doFinal(m7Nonce);
                    System.out.println("Message 7 Decrypted nonce contents:");
                    System.out.println(ByteBuffer.wrap(m7Nonce).getLong());
                }
                catch (ClassNotFoundException | IllegalBlockSizeException | BadPaddingException e)
                {
                    e.printStackTrace();
                }


                // MESSAGE 8 (PREMASTER SECRET)
                System.out.println();
                System.out.println("Message 8 (SSL3_MT_CLIENT_KEY_EXCHANGE): ");
                byte[] preMasterSecretSave = null;
                SSLRecordHeader m8Header = null;
                try
                {
                    m8Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 8 header received. Header contents:");
                    m8Header.printHeader();
                    // Read in premaster secret
                    preMasterSecretSave = (byte[]) inStream.readObject();
                    byte[] preMasterSecret = preMasterSecretSave.clone();
                    // add secret for MD computation
                    mdList.add(m8Header);
                    mdList.add(preMasterSecret);

                    System.out.println(
                            "Message 8 preMaster secret received. Encrypted secret contents:");
                    System.out.println(ByteBuffer.wrap(preMasterSecret).getLong());

                    // Decrypt nonce:
                    preMasterSecret = this.decRSACipher.doFinal(preMasterSecret);
                    System.out.println("Message 8 Decrypted preMaster contents:");
                    System.out.println(ByteBuffer.wrap(preMasterSecret).getLong());

                    // Generate AES and HMAC keys using preMaster secret
                    this.aesKey = generateAESKey(preMasterSecret);
                    this.hmacKey = generateHMACKey(preMasterSecret);
                }
                catch (ClassNotFoundException | IllegalBlockSizeException | BadPaddingException
                        | NoSuchAlgorithmException e)
                {
                    e.printStackTrace();
                }

                // MESSAGE DIGEST COMP
                // Create a list to store all computed MD
                System.out.println();
                ArrayList<byte[]> byteList = new ArrayList<byte[]>();
                try
                {
                    String id = "SERVER";
                    byte[] key = new byte[preMasterSecretSave.length + id.getBytes().length];
                    // Set the key for the hash
                    SecretKeySpec secretKeySpec = new SecretKeySpec(key, "HmacSHA1");
                    // Initialize the Mac with the secret key
                    Mac mac = Mac.getInstance("HmacSHA1");
                    mac.init(secretKeySpec);
                    // Baos for serializing objects in mdList
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();

                    for (Object o : mdList)
                    {
                        // Serialize object
                        ObjectOutputStream oos = new ObjectOutputStream(baos);
                        // write serialized obj to baos
                        oos.writeObject(o);
                        oos.flush();
                        byte[] serialized = baos.toByteArray();
                        baos.reset();
                        byte[] hash = mac.doFinal(serialized);
                        byteList.add(hash);
                    }
                }
                catch (NoSuchAlgorithmException | InvalidKeyException e)
                {
                    e.printStackTrace();
                }
                // Print out message digest
                System.out.println();
                System.out.println("Message digest computed using keyed SHA-1: ");
                for (byte[] byteArray : byteList)
                {
                    System.out.println(Arrays.toString(byteArray));
                }
                System.out.println();

                // MESSAGE 9
                System.out.println();
                System.out.println("Message 9 (SSL3_MT_FINISHED): ");
                // Create header for mac
                long m9MacSize = getObjectSize(byteList);
                System.out.println("Server: size of mac being sent: " + m9MacSize);
                long[] m9ContentLength = {m9MacSize};
                SSLRecordHeader m9Header = new SSLRecordHeader(SSLRecordHeader.TLS1_3_VERSION,
                        SSLRecordHeader.SSL3_MT_FINISHED, m9ContentLength);

                // Transmit mac to client
                outStream.writeObject(m9Header);
                outStream.writeObject(byteList);

                // Compute expected Client MD
                // Create a list to store all computed MD
                ArrayList<byte[]> byteList2 = new ArrayList<byte[]>();
                String id = "CLIENT";
                byte[] key = new byte[preMasterSecretSave.length + id.getBytes().length];
                // Set the key for the hash
                SecretKeySpec secretKeySpec = new SecretKeySpec(key, "HmacSHA1");

                // Initialize the Mac with the secret key
                Mac mac;
                try
                {
                    mac = Mac.getInstance("HmacSHA1");
                    mac.init(secretKeySpec);
                    // Baos for serializing objects in mdList
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();

                    for (Object o : mdList)
                    {
                        // Serialize object
                        ObjectOutputStream oos = new ObjectOutputStream(baos);
                        // write serialized obj to baos
                        oos.writeObject(o);
                        oos.flush();
                        byte[] serialized = baos.toByteArray();
                        baos.reset();
                        byte[] hash = mac.doFinal(serialized);
                        byteList2.add(hash);
                    }
                }
                catch (NoSuchAlgorithmException | InvalidKeyException e)
                {
                    e.printStackTrace();
                }

                // MESSAGE 10
                System.out.println();
                System.out.println("Message 10 (TLS1_AD_DECRYPTION_FAILED): ");
                // Receive err from client
                try
                {
                    SSLRecordHeader m10Header = (SSLRecordHeader) inStream.readObject();
                    System.out.println("Message 10 header received. Header contents:");
                    m10Header.printHeader();

                    String errMessage = (String) inStream.readObject();
                    System.out.println("Server: Client's message received: " + errMessage);
                    System.out.println(
                            "Server: Huh, I'm not sure what happended. Oh well, bye client!");
                }
                catch (ClassNotFoundException e)
                {
                    e.printStackTrace();
                }

                System.out.println();
                // HANDSHAKE PROTOCOL FINISHED

                // Servers done. Close shop
                openSocket = false;
                // close client socket and in/out streams
                client.close();
                inStream.close();
                outStream.close();
            }
            // close server socket
            serverSocket.close();
        }
        catch (UnknownHostException ex)
        {
            LOGGER.log(java.util.logging.Level.SEVERE, null, ex);
        }
        catch (IOException e)
        {
            LOGGER.log(java.util.logging.Level.SEVERE, null, e);
        }
    }

    public static boolean compareArrayLists(ArrayList<byte[]> list1, ArrayList<byte[]> list2)
    {
        if (list1.size() != list2.size())
        {
            return false;
        }
        // Sort the lists
        Collections.sort(list1, Comparator.comparing(Arrays::hashCode));
        Collections.sort(list2, Comparator.comparing(Arrays::hashCode));
        // Compare the lists
        for (int i = 0; i < list1.size(); i++)
        {
            byte[] arr1 = list1.get(i);
            byte[] arr2 = list2.get(i);
            if (!Arrays.equals(arr1, arr2))
            {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception
    {
        CertificateAuthority ca = new CertificateAuthority();
        Server s = new Server();
        //s.sslHandshakeSuccess();
        s.sslHandshakeFail();
    }
}
