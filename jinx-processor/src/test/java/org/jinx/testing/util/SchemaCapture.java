package org.jinx.testing.util;

import org.jinx.model.EntityModel;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

public final class SchemaCapture {
    public static EntityModel capturePutIfAbsent(Map<String, EntityModel> map, String key) {
        ArgumentCaptor<EntityModel> cap = ArgumentCaptor.forClass(EntityModel.class);
        verify(map).putIfAbsent(eq(key), cap.capture());
        return cap.getValue();
    }
}