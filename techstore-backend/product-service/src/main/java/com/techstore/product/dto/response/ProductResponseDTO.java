package com.techstore.product.dto.response;

import java.io.Serializable;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductResponseDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String description;

    private Double basePrice;

    private Double performanceScore;
    private Double powerConsumption;
    private String status;

    private BrandResponseDTO brand;
    private CategoryResponseDTO category;

    private List<ProductImageResponseDTO> images;
    private List<ProductSpecResponseDTO> specs;
    private List<VariantResponseDTO> variants;
}
