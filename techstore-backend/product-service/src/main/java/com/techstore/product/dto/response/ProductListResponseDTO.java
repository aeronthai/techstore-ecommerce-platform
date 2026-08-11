package com.techstore.product.dto.response;

import java.io.Serializable;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProductListResponseDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Double basePrice;
    private String status;
    private String brandName;
    private String categoryName;

    private String primaryImage;
}
