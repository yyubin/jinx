package org.jinx.model.naming;

public enum CaseStrategy implements CaseNormalizer{
    LOWER { public String normalize(String s){ return CaseNormalizer.lower().normalize(s); } },
    UPPER { public String normalize(String s){ return CaseNormalizer.upper().normalize(s); } },
    PRESERVE { public String normalize(String s){ return CaseNormalizer.preserve().normalize(s); } };
}
