package com.safehaven.ui;

import com.safehaven.model.User;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.security.auth.DestroyFailedException;
import java.lang.reflect.Field;
import java.security.PrivateKey;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainControllerSessionTest {

    @Test
    void clearSessionSecrets_destroysKeysAndNullsReferences() throws Exception {
        MainController controller = new MainController();
        try {
            SecretKey secretKey = mock(SecretKey.class);
            PrivateKey privateKey = mock(PrivateKey.class);

            setField(controller, "currentUser", new User("alice", new byte[32], new byte[16]));
            setField(controller, "secretKey", secretKey);
            setField(controller, "privateKey", privateKey);

            controller.clearSessionSecrets();

            verify(secretKey).destroy();
            verify(privateKey).destroy();
            assertNull(getField(controller, "currentUser"));
            assertNull(getField(controller, "secretKey"));
            assertNull(getField(controller, "privateKey"));
        } finally {
            shutdownExecutor(controller);
        }
    }

    @Test
    void failedKeyDestruction_stillClearsEverySessionReference() throws Exception {
        MainController controller = new MainController();
        try {
            SecretKey secretKey = mock(SecretKey.class);
            PrivateKey privateKey = mock(PrivateKey.class);
            doThrow(new DestroyFailedException("simulated destroy failure"))
                    .when(secretKey).destroy();

            setField(controller, "currentUser", new User("alice", new byte[32], new byte[16]));
            setField(controller, "secretKey", secretKey);
            setField(controller, "privateKey", privateKey);

            assertDoesNotThrow(controller::clearSessionSecrets,
                    "A destroy failure must not prevent session cleanup");

            verify(secretKey).destroy();
            verify(privateKey).destroy();
            assertNull(getField(controller, "currentUser"));
            assertNull(getField(controller, "secretKey"));
            assertNull(getField(controller, "privateKey"));
        } finally {
            shutdownExecutor(controller);
        }
    }

    @Test
    void realSecretKey_isActuallyDestroyed() throws Exception {
        MainController controller = new MainController();
        try {
            DestroyableKey secretKey = new DestroyableKey();
            setField(controller, "secretKey", secretKey);

            controller.clearSessionSecrets();

            assertTrue(secretKey.isDestroyed(),
                    "SecretKey.destroy() must be invoked on logout");
            assertNull(getField(controller, "secretKey"));
        } finally {
            shutdownExecutor(controller);
        }
    }

    private static final class DestroyableKey implements SecretKey {
        private boolean destroyed;

        @Override
        public String getAlgorithm() {
            return "AES";
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return destroyed ? null : new byte[32];
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isDestroyed() {
            return destroyed;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void shutdownExecutor(MainController controller) throws Exception {
        ExecutorService executor = (ExecutorService) getField(controller, "executor");
        executor.shutdownNow();
    }
}
