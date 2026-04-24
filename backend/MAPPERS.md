# Mappers

Static converter classes in `helper/mapper/`. One class per entity. Pattern copied from `document-manager`'s mapper package.

## Location & naming

```
helper/mapper/
├── ObligationMapper.java
├── ControlMapper.java
├── MappingMapper.java
├── GapMapper.java
└── EvidenceMapper.java
```

## Class shape

Static methods only, no state. Lombok `@Builder` on the DTO side.

```java
package com.bunq.javabackend.helper.mapper;

import com.bunq.javabackend.dto.response.ObligationResponseDTO;
import com.bunq.javabackend.model.Obligation;

public class ObligationMapper {

    public static ObligationResponseDTO toDto(Obligation source) {
        return ObligationResponseDTO.builder()
                .id(source.getId())
                .deontic(source.getDeontic())
                .subject(source.getSubject())
                .action(source.getAction())
                .riskCategory(source.getRiskCategory())
                .extractionConfidence(source.getExtractionConfidence())
                .build();
    }

    public static Obligation toModel(ObligationResponseDTO dto) {
        var target = new Obligation();
        target.setId(dto.getId());
        target.setDeontic(dto.getDeontic());
        target.setSubject(dto.getSubject());
        target.setAction(dto.getAction());
        return target;
    }
}
```

## Rules

- One mapper class per entity — not one giant `EntityMapper`
- Map only fields that exist on both sides
- If a field is used in more than one place → mapper is mandatory
- If a field is used once, inline conversion in service is acceptable but mapper is preferred
- Nested objects: map each level separately (e.g. `ObligationMapper` calls `ObligationSourceMapper` if needed)
- Never inject mappers into services — call them as static imports or direct invocation
