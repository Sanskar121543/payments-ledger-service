package io.github.sanskar121543.payments.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InstanceInfo {

    private final String id;

    public InstanceInfo(@Value("${payments.instance-id}") String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
