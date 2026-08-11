package com.techstore.product.dto.response;

import java.io.Serializable;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductAIResponseDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Double basePrice;

    private String categoryType;
    private String pcComponentType;
    private Double performanceScore;
    private Double powerConsumption;

    private String primaryImage;

    private List<ProductSpecDTO> specs;
}
