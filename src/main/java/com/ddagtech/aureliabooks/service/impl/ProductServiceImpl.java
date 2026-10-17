package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.AuthorSummary;
import com.ddagtech.aureliabooks.dto.response.BrandSummary;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductAutoCompleteResponse;
import com.ddagtech.aureliabooks.dto.response.ProductDetailResponse;
import com.ddagtech.aureliabooks.dto.response.PublisherSummary;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.mapper.MasterDataMapper;
import com.ddagtech.aureliabooks.mapper.ProductMapper;
import com.ddagtech.aureliabooks.repository.AuthorRepository;
import com.ddagtech.aureliabooks.repository.BookRepository;
import com.ddagtech.aureliabooks.repository.BrandRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.PublisherRepository;
import com.ddagtech.aureliabooks.repository.StationeryRepository;
import com.ddagtech.aureliabooks.repository.specification.ProductSpecification;
import com.ddagtech.aureliabooks.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Service implementation for product catalog operations (UC01, UC02, UC03).
 */
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BookRepository bookRepository;
    private final StationeryRepository stationeryRepository;
    private final AuthorRepository authorRepository;
    private final PublisherRepository publisherRepository;
    private final BrandRepository brandRepository;
    private ProductMapper productMapper = new ProductMapper();
    private MasterDataMapper masterDataMapper = new MasterDataMapper();

    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              BookRepository bookRepository) {
        this(productRepository, categoryRepository, bookRepository, null, null, null, null);
    }

    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              BookRepository bookRepository,
                              StationeryRepository stationeryRepository) {
        this(productRepository, categoryRepository, bookRepository, stationeryRepository, null, null, null);
    }

    @Autowired
    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              BookRepository bookRepository,
                              StationeryRepository stationeryRepository,
                              AuthorRepository authorRepository,
                              PublisherRepository publisherRepository,
                              BrandRepository brandRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
        this.stationeryRepository = stationeryRepository;
        this.authorRepository = authorRepository;
        this.publisherRepository = publisherRepository;
        this.brandRepository = brandRepository;
    }

    @Autowired(required = false)
    public void setProductMapper(ProductMapper productMapper) {
        if (productMapper != null) {
            this.productMapper = productMapper;
        }
    }

    @Autowired(required = false)
    public void setMasterDataMapper(MasterDataMapper masterDataMapper) {
        if (masterDataMapper != null) {
            this.masterDataMapper = masterDataMapper;
        }
    }

    @Transactional(readOnly = true)
    @Override
    public Page<ProductSummary> browse(ProductFilterRequest filter, Pageable pageable) {
        Specification<Product> spec = ProductSpecification.isActive(true);
        spec = spec.and((root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), 0));

        Pageable effectivePageable = pageable;
        if (filter != null) {
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                spec = spec.and(ProductSpecification.hasKeyword(filter.keyword()));
                spec = spec.and(ProductSpecification.prioritizeExactIdentifier(filter.keyword(), pageable.getSort()));

                effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.unsorted());
            }
            if (filter.categoryId() != null) {
                List<Long> targetCategoryIds = new ArrayList<>();
                targetCategoryIds.add(filter.categoryId());
                if (categoryRepository != null) {
                    List<Category> children = categoryRepository.findByParentId(filter.categoryId());
                    if (children != null && !children.isEmpty()) {
                        for (Category child : children) {
                            targetCategoryIds.add(child.getId());
                        }
                    }
                }
                spec = spec.and(ProductSpecification.hasCategoryIn(targetCategoryIds));
            }
            if (filter.minPrice() != null || filter.maxPrice() != null) {
                spec = spec.and(ProductSpecification.priceBetween(filter.minPrice(), filter.maxPrice()));
            }
            if (filter.authorId() != null) {
                spec = spec.and(ProductSpecification.hasAuthor(filter.authorId()));
            }
            if (filter.publisherId() != null) {
                spec = spec.and(ProductSpecification.hasPublisher(filter.publisherId()));
            }
            if (filter.brandId() != null) {
                spec = spec.and(ProductSpecification.hasBrand(filter.brandId()));
            }
            if (filter.coverType() != null) {
                spec = spec.and(ProductSpecification.hasCoverType(filter.coverType()));
            }
            if (filter.productType() != null) {
                spec = spec.and(ProductSpecification.hasProductType(filter.productType()));
            }
            if (filter.isTextbook() != null) {
                spec = spec.and(ProductSpecification.isTextbook(filter.isTextbook()));
            }
        }

        Page<Product> productPage = productRepository.findAll(spec, effectivePageable);

        // Batch fetch book details in a single query to eliminate N+1 problem
        List<Long> bookProductIds = new ArrayList<>();
        for (Product p : productPage) {
            if (p.getProductType() == Product.ProductType.BOOK) {
                bookProductIds.add(p.getId());
            }
        }

        Map<Long, Book> bookMap = new HashMap<>();
        if (!bookProductIds.isEmpty()) {
            for (Book book : bookRepository.findAllById(bookProductIds)) {
                bookMap.put(book.getProductId(), book);
            }
        }

        return productPage.map(product -> ProductMapper.toSummary(
                product, bookMap.get(product.getId())));
    }

    @Transactional(readOnly = true)
    @Override
    public ProductDetailResponse viewDetail(Long productId) {
        Product product = productRepository.findById(productId)
                .filter(Product::getIsActive)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + productId));

        if (product.getProductType() == Product.ProductType.BOOK) {
            var bookOpt = bookRepository.findById(productId);
            if (bookOpt.isPresent()) {
                return productMapper.toBookDetail(product, bookOpt.get());
            }
        } else if (product.getProductType() == Product.ProductType.STATIONERY && stationeryRepository != null) {
            var stationeryOpt = stationeryRepository.findById(productId);
            if (stationeryOpt.isPresent()) {
                return productMapper.toStationeryDetail(product, stationeryOpt.get());
            }
        }

        return productMapper.toGenericDetail(product);
    }

    @Override
    public List<CategorySummary> getActiveCategories() {
        return categoryRepository.findByIsActiveTrue().stream()
                .map(masterDataMapper::toCategorySummary)
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    public List<AuthorSummary> getAuthors() {
        return authorRepository.findAll(Sort.by("name")).stream()
                .map(masterDataMapper::toAuthorSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    public List<PublisherSummary> getPublishers() {
        return publisherRepository.findAll(Sort.by("name")).stream()
                .map(masterDataMapper::toPublisherSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    public List<BrandSummary> getBrands() {
        return brandRepository.findAll(Sort.by("name")).stream()
                .map(masterDataMapper::toBrandSummary)
                .toList();
    }

    @Override
    public List<ProductAutoCompleteResponse> autoComplete(String keyword) {
        if (keyword == null || keyword.trim().length() < 2) {
            return List.of();
        }

        Specification<Product> spec = ProductSpecification.isActive(true)
                .and((root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), 0))
                .and(ProductSpecification.hasKeyword(keyword.trim()))
                .and(ProductSpecification.prioritizeExactIdentifier(keyword.trim()));

        Pageable limitFive = PageRequest.of(0, 5);
        Page<Product> products = productRepository.findAll(spec, limitFive);

        return products.getContent().stream()
                .map(productMapper::toAutoComplete)
                .toList();
    }
}
