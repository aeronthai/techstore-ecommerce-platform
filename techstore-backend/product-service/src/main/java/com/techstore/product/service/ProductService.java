package com.techstore.product.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.techstore.product.client.FileServiceClient;
import com.techstore.product.client.WarehouseServiceClient;
import com.techstore.product.configuration.CacheNames;
import com.techstore.product.constant.UploadFolder;
import com.techstore.product.dto.request.ProductCreateRequestDTO;
import com.techstore.product.dto.request.ProductSearchRequestDTO;
import com.techstore.product.dto.request.ProductStatusUpdateRequestDTO;
import com.techstore.product.dto.request.ProductUpdateImageRequestDTO;
import com.techstore.product.dto.request.ProductUpdateRequestDTO;
import com.techstore.product.dto.response.FileResponse;
import com.techstore.product.dto.response.PageResponseDTO;
import com.techstore.product.dto.response.ProductAIResponseDTO;
import com.techstore.product.dto.response.ProductListResponseDTO;
import com.techstore.product.dto.response.ProductResponseDTO;
import com.techstore.product.dto.response.ProductSpecDTO;
import com.techstore.product.entity.Brand;
import com.techstore.product.entity.Category;
import com.techstore.product.entity.Product;
import com.techstore.product.entity.ProductImage;
import com.techstore.product.entity.ProductSpec;
import com.techstore.product.exception.AppException;
import com.techstore.product.exception.ErrorCode;
import com.techstore.product.mapper.ProductMapper;
import com.techstore.product.repository.BrandRepository;
import com.techstore.product.repository.CategoryRepository;
import com.techstore.product.repository.ProductRepository;

@Service
@Transactional
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private FileServiceClient fileServiceClient;

    @Autowired
    private WarehouseServiceClient warehouseServiceClient;

    /**
     * Thêm sản phẩm mới (yêu cầu role ADMIN)
     */
    @Caching(
            evict = {
                @CacheEvict(value = CacheNames.PRODUCT_DETAIL, key = "#result.id"),
                @CacheEvict(value = CacheNames.PRODUCT_LIST, allEntries = true),
                @CacheEvict(value = CacheNames.PRODUCT_AI, allEntries = true)
            })
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductResponseDTO createProduct(ProductCreateRequestDTO req) {

        Brand brand = brandRepository
                .findById(req.getBrandId())
                .orElseThrow(() -> new AppException(ErrorCode.BRAND_NOT_FOUND));

        Category category = categoryRepository
                .findById(req.getCategoryId())
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));

        Product product = productMapper.toEntity(req);
        product.setBrand(brand);
        product.setCategory(category);
        product.setStatus("DRAFT");

        if (req.getSpecs() != null && !req.getSpecs().isEmpty()) {

            Set<ProductSpec> specs = req.getSpecs().stream()
                    .map(dto -> {
                        ProductSpec spec = new ProductSpec();
                        spec.setSpecKey(dto.getSpecKey());
                        spec.setSpecValue(dto.getSpecValue());
                        spec.setProduct(product);
                        return spec;
                    })
                    .collect(Collectors.toSet());

            product.setSpecs(specs);
        }

        Product saved = productRepository.save(product);
        return productMapper.toResponseDTO(saved);
    }

    /**
     * Cập nhật thông tin sản phẩm (yêu cầu role ADMIN)
     * Chỉ cập nhật những trường được gửi hoặc có giá trị
     */
    @Caching(
            evict = {
                @CacheEvict(value = CacheNames.PRODUCT_DETAIL, key = "#id"),
                @CacheEvict(value = CacheNames.PRODUCT_LIST, allEntries = true),
                @CacheEvict(value = CacheNames.PRODUCT_AI, allEntries = true)
            })
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductResponseDTO updateProductInfo(Long id, ProductUpdateRequestDTO req) {

        Product product =
                productRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        // 1. Update field đơn
        productMapper.updateEntity(req, product);

        // 2. Brand
        if (req.getBrandId() != null) {
            Brand brand = brandRepository
                    .findById(req.getBrandId())
                    .orElseThrow(() -> new AppException(ErrorCode.BRAND_NOT_FOUND));
            product.setBrand(brand);
        }

        // 3. Category
        if (req.getCategoryId() != null) {
            Category category = categoryRepository
                    .findById(req.getCategoryId())
                    .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
            product.setCategory(category);
        }

        // 4. Specs
        if (req.getSpecs() != null) {

            if (product.getSpecs() == null) {
                product.setSpecs(new HashSet<>());
            } else {
                product.getSpecs().clear();
            }

            for (var dto : req.getSpecs()) {
                ProductSpec spec = new ProductSpec();
                spec.setSpecKey(dto.getSpecKey());
                spec.setSpecValue(dto.getSpecValue());
                spec.setProduct(product);

                product.getSpecs().add(spec);
            }
        }

        Product saved = productRepository.save(product);
        return productMapper.toResponseDTO(saved);
    }

    @Caching(
            evict = {
                @CacheEvict(value = CacheNames.PRODUCT_DETAIL, key = "#productId"),
                @CacheEvict(value = CacheNames.PRODUCT_LIST, allEntries = true)
            })
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductResponseDTO updateProductImages(
            Long productId, MultipartFile[] files, List<ProductUpdateImageRequestDTO> imageDTOs) {

        Product product =
                productRepository.findById(productId).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        Set<ProductImage> existingImages = product.getImages();

        if (existingImages == null) {
            existingImages = new HashSet<>();
            product.setImages(existingImages);
        }

        /* =========================
        1️ UPLOAD FILE (NẾU CÓ)
        ========================= */
        List<FileResponse> uploadedFiles = List.of();

        if (files != null && files.length > 0) {

            if (existingImages.size() + files.length > 10) {
                throw new AppException(ErrorCode.PRODUCT_IMAGE_LIMIT_EXCEEDED);
            }

            uploadedFiles = fileServiceClient
                    .uploadMultiple(files, UploadFolder.PRODUCT_IMAGE.name())
                    .getResult();
        }

        /* =========================
        2️ UPDATE / REPLACE / ADD
        ========================= */
        if (imageDTOs != null && !imageDTOs.isEmpty()) {

            for (ProductUpdateImageRequestDTO dto : imageDTOs) {

                ProductImage targetImage = null;

                //  Tìm ảnh cũ nếu có oldUrl
                if (dto.getOldUrl() != null) {
                    String normalizedOldUrl = normalizeImagePath(dto.getOldUrl());

                    targetImage = existingImages.stream()
                            .filter(img -> normalizeImagePath(img.getUrl()).equals(normalizedOldUrl))
                            .findFirst()
                            .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_IMAGE_NOT_FOUND));
                }

                //  REPLACE bằng file mới
                if (dto.getFileIndex() != null) {

                    if (dto.getFileIndex() < 0 || dto.getFileIndex() >= uploadedFiles.size()) {
                        throw new AppException(ErrorCode.INVALID_FILE_INDEX);
                    }

                    FileResponse file = uploadedFiles.get(dto.getFileIndex());

                    if (targetImage != null) {
                        // replace ảnh cũ
                        targetImage.setUrl(file.getUrl());
                    } else {
                        // add ảnh mới
                        ProductImage newImage = new ProductImage();
                        newImage.setProduct(product);
                        newImage.setUrl(file.getUrl());
                        newImage.setIsPrimary(false);
                        existingImages.add(newImage);
                        targetImage = newImage;
                    }
                }

                // UPDATE PRIMARY
                if (Boolean.TRUE.equals(dto.getIsPrimary()) && targetImage != null) {

                    // ❗ Clear toàn bộ primary cũ
                    for (ProductImage img : existingImages) {
                        img.setIsPrimary(false);
                    }

                    // ❗ Set primary cho ảnh được chỉ định
                    targetImage.setIsPrimary(true);
                }
            }
        }

        /* =========================
        3️ ENSURE PRIMARY
        ========================= */
        ensurePrimaryImage(existingImages);

        return productMapper.toResponseDTO(productRepository.save(product));
    }

    //   OLD
    //    @PreAuthorize("hasRole('ADMIN')")
    //    public ProductResponseDTO updateProduct(Long id, ProductUpdateRequestDTO requestDTO) {
    //        Product product =
    //                productRepository.findDetailById(id).orElseThrow(() -> new
    // AppException(ErrorCode.PRODUCT_NOT_FOUND));
    //
    //        // Update basic fields (only if provided)
    //        if (requestDTO.getName() != null) {
    //            product.setName(requestDTO.getName());
    //        }
    //        if (requestDTO.getDescription() != null) {
    //            product.setDescription(requestDTO.getDescription());
    //        }
    //        if (requestDTO.getPerformanceScore() != null) {
    //            product.setPerformanceScore(requestDTO.getPerformanceScore());
    //        }
    //        if (requestDTO.getPowerConsumption() != null) {
    //            product.setPowerConsumption(requestDTO.getPowerConsumption());
    //        }
    //        if (requestDTO.getStatus() != null) {
    //            product.setStatus(requestDTO.getStatus());
    //        }
    //
    //        // Update brand if provided
    //        if (requestDTO.getBrandId() != null) {
    //            Brand brand = brandRepository
    //                    .findById(requestDTO.getBrandId())
    //                    .orElseThrow(() -> new AppException(ErrorCode.BRAND_NOT_FOUND));
    //            product.setBrand(brand);
    //        }
    //
    //        // Update category if provided
    //        if (requestDTO.getCategoryId() != null) {
    //            Category category = categoryRepository
    //                    .findById(requestDTO.getCategoryId())
    //                    .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
    //            product.setCategory(category);
    //        }
    //
    //        // Update images if provided
    //        if (requestDTO.getImages() != null) {
    //            product.getImages().clear();
    //
    //            List<ProductImage> newImages = requestDTO.getImages().stream()
    //                    .map(imageDTO -> {
    //                        ProductImage image = productImageMapper.toEntity(imageDTO);
    //                        image.setProduct(product);
    //                        return image;
    //                    })
    //                    .toList();
    //
    //            product.getImages().addAll(newImages);
    //        }
    //
    //        // Update specs if provided
    //        if (requestDTO.getSpecs() != null) {
    //            // Remove old specs
    //            if (product.getSpecs() != null) {
    //                product.getSpecs().clear();
    //            }
    //
    //            // Add new specs
    //            List<ProductSpec> newSpecs = requestDTO.getSpecs().stream()
    //                    .map(specDTO -> {
    //                        ProductSpec spec = productSpecMapper.toEntity(specDTO);
    //                        spec.setProduct(product);
    //                        return spec;
    //                    })
    //                    .toList();
    //            product.getSpecs().addAll(newSpecs);
    //        }
    //
    //        Product updatedProduct = productRepository.save(product);
    //        return productMapper.toResponseDTO(updatedProduct);
    //    }

    /**
     * Cập nhật trạng thái sản phẩm (yêu cầu role ADMIN)
     */
    @Caching(
            evict = {
                @CacheEvict(value = CacheNames.PRODUCT_DETAIL, key = "#id"),
                @CacheEvict(value = CacheNames.PRODUCT_LIST, allEntries = true),
                @CacheEvict(value = CacheNames.PRODUCT_AI, allEntries = true)
            })
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponseDTO updateProductStatus(Long id, ProductStatusUpdateRequestDTO requestDTO) {
        Product product =
                productRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        product.setStatus(requestDTO.getStatus());
        Product updatedProduct = productRepository.save(product);
        return productMapper.toResponseDTO(updatedProduct);
    }

    /**
     * Lấy sản phẩm theo ID
     */
    @Cacheable(value = CacheNames.PRODUCT_DETAIL, key = "#id")
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long id) {

        Product product =
                productRepository.findDetailById(id).orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        ProductResponseDTO dto = productMapper.toResponseDTO(product);

        // Nếu DTO có danh sách variants
        if (dto.getVariants() != null) {
            dto.getVariants().forEach(variantDTO -> {
                try {
                    Long stock = warehouseServiceClient
                            .getTotalStockByVariant(variantDTO.getId())
                            .getResult();

                    variantDTO.setStock(stock);
                } catch (Exception e) {
                    variantDTO.setStock(null); // UNKNOWN
                }
            });
        }

        return dto;
    }
    /**
     * Lấy danh sách tất cả sản phẩm (có phân trang)
     */
    @Cacheable(
            value = CacheNames.PRODUCT_LIST,
            key = "'all:' + #page + ':' + #size + ':' + #sortBy + ':' + #sortDirection")
    @Transactional(readOnly = true)
    public PageResponseDTO<ProductListResponseDTO> getAllProducts(
            int page, int size, String sortBy, String sortDirection) {
        Sort sort = sortDirection.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Product> productPage = productRepository.findAll(pageable);

        List<ProductListResponseDTO> content = productMapper.toListResponseDTOList(productPage.getContent());

        return new PageResponseDTO<>(
                content,
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast());
    }

    /**
     * Lấy danh sách sản phẩm theo tên thể loại
     */
    @Transactional(readOnly = true)
    public PageResponseDTO<ProductListResponseDTO> getProductsByCategoryType(
            String categoryType, int page, int size, String sortBy, String sortDirection) {

        Sort sort = sortDirection.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Product> productPage = productRepository.findByCategoryType(categoryType, pageable);

        List<ProductListResponseDTO> content = productMapper.toListResponseDTOList(productPage.getContent());

        return new PageResponseDTO<>(
                content,
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast());
    }

    @Cacheable(
            value = CacheNames.PRODUCT_LIST,
            key = "'cat:' + #categoryId + ':' + #page + ':' + #size + ':' + #sortBy + ':' + #sortDirection")
    @Transactional(readOnly = true)
    public PageResponseDTO<ProductListResponseDTO> getProductsByCategoryId(
            Long categoryId,
            int page,
            int size,
            String sortBy,
            String sortDirection,
            List<Long> brandIds,
            Double minPrice,
            Double maxPrice) {

        // validate
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new IllegalArgumentException("minPrice không được lớn hơn maxPrice");
        }

        Sort sort = sortDirection.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> productPage =
                productRepository.searchProductsV2(brandIds, List.of(categoryId), minPrice, maxPrice, pageable);

        List<ProductListResponseDTO> content = productMapper.toListResponseDTOList(productPage.getContent());

        return new PageResponseDTO<>(
                content,
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast());
    }

    /**
     * Lấy n sản phẩm mới nhất
     */
    @Transactional(readOnly = true)
    public List<ProductListResponseDTO> getLatestProducts(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<Product> products = productRepository.findLatestProducts(pageable);
        return productMapper.toListResponseDTOList(products);
    }

    /**
     * Tìm kiếm sản phẩm theo tên sản phẩm, tên danh mục, tên thương hiệu
     * Có hỗ trợ lọc theo brand và khoảng giá
     */
    @Transactional(readOnly = true)
    public PageResponseDTO<ProductListResponseDTO> searchProducts(ProductSearchRequestDTO searchDTO) {
        Sort sort = searchDTO.getSortDirection().equalsIgnoreCase("ASC")
                ? Sort.by(searchDTO.getSortBy()).ascending()
                : Sort.by(searchDTO.getSortBy()).descending();

        Pageable pageable = PageRequest.of(searchDTO.getPage(), searchDTO.getSize(), sort);

        Page<Product> productPage = productRepository.searchProducts(
                searchDTO.getKeyword(),
                normalize(searchDTO.getBrandNames()),
                normalize(searchDTO.getCategoryIds()),
                searchDTO.getMinPrice(),
                searchDTO.getMaxPrice(),
                pageable);

        List<ProductListResponseDTO> content = productMapper.toListResponseDTOList(productPage.getContent());

        return new PageResponseDTO<>(
                content,
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages(),
                productPage.isLast());
    }

    public ProductResponseDTO findByVariantId(Long variantId) {
        Product product = productRepository
                .findByVariantId(variantId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
        return productMapper.toResponseDTO(product);
    }

    @Cacheable(value = CacheNames.PRODUCT_AI, key = "'pc-components'")
    @Transactional(readOnly = true)
    public List<ProductAIResponseDTO> getPCComponentsForAI() {

        List<String> types = List.of("SSD", "RAM", "PSU", "MAINBOARD", "HDD", "GPU", "CASE");

        List<Product> products = productRepository.findPCComponentsForAI(types);

        return products.stream()
                .map(p -> {
                    ProductAIResponseDTO dto = new ProductAIResponseDTO();

                    dto.setId(p.getId());
                    dto.setName(p.getName());
                    dto.setBasePrice(p.getBasePrice());
                    dto.setPerformanceScore(p.getPerformanceScore());
                    dto.setPowerConsumption(p.getPowerConsumption());

                    if (p.getCategory() != null) {
                        dto.setCategoryType(p.getCategory().getCategoryType());
                        dto.setPcComponentType(p.getCategory().getPcComponentType());
                    }

                    dto.setPrimaryImage(p.getImages().stream()
                            .filter(img -> Boolean.TRUE.equals(img.getIsPrimary()))
                            .map(ProductImage::getUrl)
                            .findFirst()
                            .orElse(p.getImages().stream()
                                    .findFirst()
                                    .map(ProductImage::getUrl)
                                    .orElse(null)));

                    dto.setSpecs(p.getSpecs().stream()
                            .map(spec -> {
                                ProductSpecDTO s = new ProductSpecDTO();
                                s.setSpecKey(spec.getSpecKey());
                                s.setSpecValue(spec.getSpecValue());
                                return s;
                            })
                            .toList());

                    return dto;
                })
                .toList();
    }

    private void ensurePrimaryImage(Set<ProductImage> images) {

        List<ProductImage> primaries = images.stream()
                .filter(img -> Boolean.TRUE.equals(img.getIsPrimary()))
                .toList();

        if (primaries.isEmpty() && !images.isEmpty()) {
            images.iterator().next().setIsPrimary(true);
            return;
        }

        if (primaries.size() > 1) {
            boolean first = true;

            for (ProductImage img : primaries) {
                if (first) {
                    first = false;
                } else {
                    img.setIsPrimary(false);
                }
            }
        }
    }

    private <T> List<T> normalize(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list;
    }

    private String normalizeImagePath(String fullPath) {
        if (fullPath == null) return null;

        fullPath = fullPath.replace("\\", "/");

        int index = fullPath.indexOf("/img/");
        if (index == -1) {
            throw new AppException(ErrorCode.INVALID_IMAGE_PATH);
        }

        return fullPath.substring(index + 1);
    }
}
