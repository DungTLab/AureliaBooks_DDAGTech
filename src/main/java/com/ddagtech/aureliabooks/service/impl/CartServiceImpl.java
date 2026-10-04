package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.response.CartItemView;
import com.ddagtech.aureliabooks.dto.response.CartView;
import com.ddagtech.aureliabooks.entity.Cart;
import com.ddagtech.aureliabooks.entity.CartItem;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.CartItemRepository;
import com.ddagtech.aureliabooks.repository.CartRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.CartService;
import org.springframework.transaction.annotation.Transactional;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public CartView viewCart(Long userId) {
        requireCustomer(userId,false);
        Cart cart = cartRepository.findByUser_Id(userId).orElse(null);

        if (cart==null){
            return new CartView(List.of(),BigDecimal.ZERO,0);
        }

        List<CartItemView> items = cartItemRepository.findAllByCart_Id(cart.getId())
                .stream().map(this::toItemView).toList();

        BigDecimal subTotal = items.stream().map(CartItemView::lineTotal).reduce(BigDecimal.ZERO,BigDecimal::add);

        int totalQuantity = items.stream().map(CartItemView::quantity).reduce(0,Integer::sum);

        return new CartView(items,subTotal,totalQuantity);
    }

    @Override
    @Transactional
    public void addItem(Long userId, Long productId, CartQuantityRequest request) {
        int quantity = validatedQuantity(request);
        User user = requireCustomer(userId,true);
        Product product = productRepository.findById(productId).orElseThrow(
                () -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,"Sản phẩm không tồn tại"));

        Cart cart = cartRepository.findByUser_Id((userId)).orElse(null);

        CartItem item = cart == null ? null : cartItemRepository.findByCart_IdAndProduct_Id(cart.getId(),productId).orElse(null);

        int resultingQuantity = quantity + (item==null ? 0 : item.getQuantity());

        validateProductAndQuality(product,resultingQuantity);

        if (cart==null){
            cart = new Cart();
            cart.setUser(user);
            cart = cartRepository.save(cart);
        }

        if (item==null){
            item = new CartItem();
            item.setCart(cart);
            item.setProduct(product);
        }

        item.setQuantity(resultingQuantity);
        cartItemRepository.save(item);
        touchCart(cart);
    }

    @Override
    @Transactional
    public void removeItem(Long userId, Long itemId) {
        requireCustomer(userId,true);
        CartItem item = findOwnedItem(itemId,userId);
        Cart cart = item.getCart();
        cartItemRepository.delete(item);
        touchCart(cart);
    }



    private int validatedQuantity(CartQuantityRequest request) {
        if (request == null
                || request.getQuantity() == null
                || request.getQuantity() < 1
                || request.getQuantity() > 99) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT_DATA,
                    "Số lượng phải là số nguyên từ 1 đến 99"
            );
        }

        return request.getQuantity();
    }

    private void validateProductAndQuality(Product product,int quantity){
        if (!product.getIsActive()){
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,"Sản phẩm hiện không được bán");
        }

        if (quantity < 1 || quantity > 99){
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tổng số lượng sản phẩm phải từ 1 đến 99");
        }

        if (quantity > product.getStockQuantity()){
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,"Không đủ tồn kho. Hiện còn " +
                    product.getStockQuantity()+ " sản phẩm" );
        }
    }

    private CartItem findOwnedItem(Long itemId, Long userId){
        return cartItemRepository.findByIdAndCart_User_Id(itemId, userId).orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,"Không tìm thấy sản phẩm trong giỏ của bạn"));
    }

    private User requireCustomer(Long userId,boolean lock){
        if (userId==null){
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        User user = (lock ? userRepository.findForCartUpdate(userId) : userRepository.findById(userId))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        if (!user.getIsActive()){
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (user.getRole()== null || !user.getRole().getRoleName().equals("ROLE_CUSTOMER")){
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        return user;
    }

    private CartItemView toItemView(CartItem item){
        Product product = item.getProduct();
        String warning = null;
        if (!product.getIsActive()){
            warning = "Sản phẩm hiện không được bán";
        } else if (product.getStockQuantity()==0){
            warning = "Sản phẩm đã hết hàng";
        } else if (item.getQuantity() > product.getStockQuantity()){
            warning = "Không đủ tồn kho. Hiện còn " + product.getStockQuantity() + " sản phẩm";
        }

        BigDecimal lineTotal =product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));

        return new CartItemView(
                item.getId(),
                product.getId(),
                product.getTitle(),
                product.getMainImageUrl(),
                product.getPrice(),
                item.getQuantity(),
                lineTotal,
                product.getStockQuantity(),
                product.getIsActive(),
                warning
        );
    }

    private void touchCart(Cart cart){
        cart.setUpdatedAt(LocalDateTime.now());
        cartRepository.save(cart);
    }
}
