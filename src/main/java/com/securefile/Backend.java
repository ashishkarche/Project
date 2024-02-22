/**
 * The `Backend` class in the `com.securefile` package provides functionality for user authentication,
 * file encryption/decryption, database operations, and file management in a secure file storage
 * application.
 */
package com.securefile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

public class Backend {
    // Database Connection Constants
    private static final String DB_URL = "jdbc:mysql://localhost:3306/filedatabase";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = ""; // Replace with your database password

    // Encryption Keys
    private static SecretKey aesSecretKey;
    private static SecretKey desSecretKey;

    // Table Names
    private static final String USER_TABLE = "users";
    private static final String ENCRYPTED_FILES_TABLE = "encrypted_files";

    // User session
    private static UserSession userSession = UserSession.getInstance();

    public static void initializeEncryptionKeys() {
        try {
            generateAESKey();
            generateDESKey();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    private static void generateAESKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        aesSecretKey = keyGen.generateKey();
    }

    private static void generateDESKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("DES");
        keyGen.init(56);
        desSecretKey = keyGen.generateKey();
    }

    public static byte[] encryptFileData(byte[] fileData) throws GeneralSecurityException, IOException {
        byte[] encryptedDataAES = encrypt(fileData, aesSecretKey, "AES");
        byte[] encryptedDataDES = encrypt(fileData, desSecretKey, "DES");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(encryptedDataAES);
        outputStream.write(encryptedDataDES);
        return outputStream.toByteArray();
    }

    public static byte[] decryptFileData(byte[] encryptedData) throws GeneralSecurityException, IOException {
        int halfLength = encryptedData.length / 2;
        byte[] encryptedDataAES = Arrays.copyOfRange(encryptedData, 0, halfLength);
        byte[] encryptedDataDES = Arrays.copyOfRange(encryptedData, halfLength, encryptedData.length);
        byte[] decryptedDataAES = decrypt(encryptedDataAES, aesSecretKey, "AES");
        byte[] decryptedDataDES = decrypt(encryptedDataDES, desSecretKey, "DES");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(decryptedDataAES);
        outputStream.write(decryptedDataDES);
        return outputStream.toByteArray();
    }

    private static byte[] encrypt(byte[] data, SecretKey key, String algorithm) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    private static byte[] decrypt(byte[] encryptedData, SecretKey key, String algorithm)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(encryptedData);
    }

    // User login authentication code
    public static boolean authenticateUser(String username, String password) {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, username, password FROM " + USER_TABLE + " WHERE username = ?")) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    int userId = resultSet.getInt("id");
                    String hashedPassword = resultSet.getString("password");
                    if (verifyPassword(password, hashedPassword)) {
                        userSession.loginUser(userId, username);
                        return true;
                    }
                }
            }
        } catch (SQLException | NoSuchAlgorithmException ex) {
            ex.printStackTrace();
        }
        return false;
    }

    // User Registration code
    public static boolean registerUser(String fullName, String username, String password) {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + USER_TABLE + " (full_name, username, password) VALUES (?, ?, ?)")) {
            statement.setString(1, fullName);
            statement.setString(2, username);
            statement.setString(3, hashPassword(password)); // Hashing the password
            int rowsAffected = statement.executeUpdate();
            return rowsAffected > 0; // If at least one row is affected, registration succeeds
        } catch (SQLException | NoSuchAlgorithmException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    public static void uploadFileToServer(String filePath, byte[] encryptedData) {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String insertSql = "INSERT INTO " + ENCRYPTED_FILES_TABLE + " (file_name, encrypted_data) VALUES (?, ?)";
            try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
                insertStatement.setString(1, Paths.get(filePath).getFileName().toString());
                insertStatement.setBytes(2, encryptedData);
                insertStatement.executeUpdate();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    public static byte[] downloadEncryptedFileFromServer(String fileName) {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String sql = "SELECT encrypted_data FROM " + ENCRYPTED_FILES_TABLE + " WHERE file_name = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, fileName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getBytes("encrypted_data");
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private static boolean verifyPassword(String password, String hashedPassword) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashedBytes = md.digest(password.getBytes());
        String hashedInputPassword = Base64.getEncoder().encodeToString(hashedBytes);
        return hashedInputPassword.equals(hashedPassword);
    }

    private static String hashPassword(String password) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashedBytes = md.digest(password.getBytes());
        return Base64.getEncoder().encodeToString(hashedBytes);
    }

    public static boolean doesFileExist(String fileName) {
        boolean exists = false;
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String sql = "SELECT COUNT(*) AS count FROM " + ENCRYPTED_FILES_TABLE + " WHERE file_name = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, fileName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        int count = resultSet.getInt("count");
                        exists = count > 0;
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return exists;
    }

    // Method to fetch file data from the server
    public static Object[][] fetchFileData() {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT file_id, file_name FROM encrypted_files")) {

            List<Object[]> dataList = new ArrayList<>();
            while (resultSet.next()) {
                int fileId = resultSet.getInt("file_id");
                String fileName = resultSet.getString("file_name");
                dataList.add(new Object[] { fileId, fileName });
            }

            Object[][] fileData = new Object[dataList.size()][2];
            for (int i = 0; i < dataList.size(); i++) {
                fileData[i] = dataList.get(i);
            }
            return fileData;

        } catch (SQLException e) {
            e.printStackTrace();
            return new Object[0][0]; // Return an empty array in case of an error
        }
    }
    
    public static boolean deleteFileFromServer(int fileId, String fileName) {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            String deleteSql = "DELETE FROM " + ENCRYPTED_FILES_TABLE + " WHERE file_id = ?";
            try (PreparedStatement deleteStatement = connection.prepareStatement(deleteSql)) {
                deleteStatement.setInt(1, fileId);
                int rowsAffected = deleteStatement.executeUpdate();
                return rowsAffected > 0;
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            return false;
        }
    }
}