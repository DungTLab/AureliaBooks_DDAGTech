# Front-End ↔ Back-End Alignment Audit Report (AureliaBooks — Baseline V2.4 · Commit `722805b`)

> **Scope & Authority:**
> - **Business Specification:** `docs/01-requirements/business-rules/0_nghiep_vu.md` (Baseline V2.4 · 20/10/2026)
> - **Database Contract:** `schema.sql` (21 physical tables + 1 read-only view `inventory_movements`)
> - **Codebase Baseline:** `origin/main` @ `722805b` (`AureliaBooks_DDAGTech`)
> - **Epistemic Tags:** `[FACT]` (directly verified in source code / `git` history) · `[ASSUMPTION]` (requires TechLead confirmation) · `[RECOMMENDATION]` (Spring Boot 3 / Thymeleaf best practice)

---

## 1. Executive Summary of Historical Audit Items vs. Current `main` (`722805b`)

Nine feature pull requests (#28, #33–#41) landed between commit `01a95dd` and `722805b`. Several items flagged in earlier audit drafts have already been resolved on `main`, while specific role-visibility misalignments in `fragments/navbar.html` and `admin/dashboard.html` remain.

| # | Audit Area | Status on `main` (`722805b`) | Evidence (`[FACT]`) | Action Taken |
|---|---|---|---|---|
| 1 | **Navbar & Staff Dashboard Role Visibility** (`fragments/navbar.html`, `admin/dashboard.html` vs. `SecurityConfig`) | **Partially Misaligned** | `navbar.html:206` exposes `/profile` to all authenticated users (`SecurityConfig:179-182` restricts `/profile/**` to `CUSTOMER`, causing HTTP 403 for staff). `navbar.html:242-263` restricts 4 `/manager/*` catalog links to `hasRole('MANAGER')` while `SecurityConfig:158-159` and `@PreAuthorize` allow `ADMIN`. `navbar.html:319` restricts `/staff/orders` to `SALE_STAFF` only. `admin/dashboard.html` omits `/staff/orders` for `SALE_STAFF` and restricts `/manager/stock/alerts` to `ADMIN/MANAGER`. | **Fixed in Commit 2** |
| 2 | **Profile Google Linking Form** (`profile/view.html`, `ProfileView`) | **Already Resolved (PR #34)** | `/profile/google/link` form was removed from `profile/view.html`; `ProfileControllerTest:131` asserts `not(containsString("/profile/google/link"))` and line 153 instantiates `ProfileView` positionally with 10 arguments. | **No change (`ProfileView` preserved)** |
| 3 | **Contact Change Re-authentication Guard** (`profile/contact-change.html`) | **Already Resolved (PR #34)** | `profile/contact-change.html:116` wraps contact mutation inputs in `<fieldset th:disabled="${!reauthenticated}">` backed by `/profile/contact-change/reauth`. | **No change** |
| 4 | **Product Detail Page (PDP) Internal Staff Purchase Guard** (`product/detail.html`) | **Already Resolved on `main`** | `product/detail.html:172-179` displays the read-only banner *"Chế độ Quản lý: Tài khoản nội bộ chỉ xem thông tin sản phẩm, không thực hiện mua hàng."* for `MANAGER`, `ADMIN`, `SALE_STAFF`, and line 202 restricts the `"Thêm Vào Giỏ Hàng"` submit button to `hasRole('CUSTOMER')`. | **Locked with automated tests in Commit 3** |
| 5 | **Goods Receipt Immutability (`RECEIVED`)** (`admin/receipts/list.html`, `AdminReceiptController`, `GoodsReceiptServiceImpl`) | **Already Synchronized** | `list.html:166,175,187` only renders Edit/Delete/Finalize buttons when `r.status == 'DRAFT'`. `AdminReceiptController:155` redirects on `RECEIVED`, and `GoodsReceiptServiceImpl:151,220,251` throws `ErrorCode.RECEIPT_ALREADY_RECEIVED` (`5002`). | **No change** |
| 6 | **Cart Quantity Clamping & Shipping Tariff Banner (Q02, Q03)** | **Already Synchronized** | `CartQuantityRequest.MAX_QUANTITY = 99` matches `cart/view.html:67` and `static/js/cart.js:5-36`. `navbar.html:25` displays *"Giao hàng toàn quốc · Cước từ 30.000đ theo vùng"* without hardcoding unconfirmed free-shipping thresholds (V2.4 §8 Q03). | **No change** |

---

## 2. Detailed Verification of Fixed FE ↔ BE Misalignments

### 2.1. Misalignment A — `/profile` Link Leaked to Internal Staff Roles (`fragments/navbar.html`)
- **`[FACT]` Back-End Enforcement:**
  - `SecurityConfig.java:179-182` configures `.requestMatchers("/cart", "/cart/**", "/checkout", "/checkout/**", "/payment", "/payment/**", "/account", "/account/**", "/orders", "/orders/**", "/profile", "/profile/**").hasRole("CUSTOMER")`.
  - `ProfileController.java` resolves the principal via `CustomerIdentity.requireCustomer(authentication)`, which throws `AppException(ErrorCode.UNAUTHORIZED)` for any non-`CUSTOMER` role.
- **`[FACT]` Front-End Defect (Before Fix):**
  - In `src/main/resources/templates/fragments/navbar.html:204-225`, `/profile/addresses` and `/orders/my-orders` were guarded by `sec:authorize="hasRole('CUSTOMER')"`, but the parent `<div class="py-1">` and the first link `<a th:href="@{/profile}">Hồ sơ cá nhân</a>` lacked `sec:authorize="hasRole('CUSTOMER')"`.
  - Consequently, `ADMIN`, `MANAGER`, and `SALE_STAFF` accounts saw `"Hồ sơ cá nhân"` in the user dropdown and received an immediate **HTTP 403 Forbidden** upon clicking it.
- **Resolution:** Added `sec:authorize="hasRole('CUSTOMER')"` to both the `<div class="py-1">` container and `<a th:href="@{/profile}">` in `fragments/navbar.html`.

### 2.2. Misalignment B — Catalog & Taxonomy Management Links Hidden from `ADMIN` (`fragments/navbar.html`)
- **`[FACT]` Back-End Enforcement:**
  - `SecurityConfig.java:158-159` grants `.requestMatchers("/manager", "/manager/**").hasAnyRole("ADMIN", "MANAGER")`.
  - `AdminProductController.java:32` and `MasterDataController.java:36` both declare `@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")`.
- **`[FACT]` Front-End Defect (Before Fix):**
  - In `src/main/resources/templates/fragments/navbar.html:242-268`, `/manager/products`, `/manager/publishers`, `/manager/brands`, and `/manager/categories` used `sec:authorize="hasRole('MANAGER')"`, hiding those links from `ADMIN` users (even though `/manager/vouchers`, `/manager/suppliers`, `/manager/analytics/revenue`, `/manager/stock/history`, and `/manager/receipts` on lines 270–303 already used `hasAnyRole('ADMIN', 'MANAGER')`).
- **Resolution:** Updated `/manager/products`, `/manager/publishers`, `/manager/brands`, and `/manager/categories` in `fragments/navbar.html` to `sec:authorize="hasAnyRole('ADMIN', 'MANAGER')"`.

### 2.3. Misalignment C — Order Processing Queue (`/staff/orders`) & Stock Alerts (`/manager/stock/alerts`) on Navbar and Staff Landing Dashboard (`fragments/navbar.html`, `admin/dashboard.html`)
- **`[FACT]` Back-End Enforcement:**
  - `RoleBasedAuthenticationSuccessHandler.java:99-103` redirects all authenticated internal roles (`ADMIN`, `MANAGER`, `SALE_STAFF`) to `/dashboard` (`DashboardController` → `templates/admin/dashboard.html`).
  - `StaffOrderController.java:78-102` and `OrderStateMachineServiceImpl.java:63,97,154` authorize `ADMIN` and `SALE_STAFF` to execute operational order transitions, carrier return restock (`confirmReturnRestock`), and external refund confirmation (`confirmExternalRefund`).
  - `SecurityConfig.java:156-157` and `AdminReceiptController.java:312` authorize `hasAnyRole('ADMIN', 'MANAGER', 'SALE_STAFF')` for `/manager/stock/alerts`.
- **`[FACT]` Front-End Defect (Before Fix):**
  - `fragments/navbar.html:319` guarded `/staff/orders` with `sec:authorize="hasRole('SALE_STAFF')"` instead of `sec:authorize="hasAnyRole('ADMIN', 'SALE_STAFF')"`.
  - `templates/admin/dashboard.html` lacked a link to `/staff/orders` altogether (leaving `SALE_STAFF` with only a single `"Nhập kho"` button upon login), omitted quick links for `/manager/vouchers`, `/manager/suppliers`, and `/manager/receipts`, and restricted `/manager/stock/alerts` to `hasAnyRole('ADMIN','MANAGER')`.
- **Resolution:**
  - Updated `/staff/orders` in `fragments/navbar.html` to `sec:authorize="hasAnyRole('ADMIN', 'SALE_STAFF')"`.
  - Updated `templates/admin/dashboard.html` so `/manager/stock/alerts` uses `sec:authorize="hasAnyRole('ADMIN','MANAGER','SALE_STAFF')"`, added `/staff/orders` for `hasAnyRole('ADMIN','SALE_STAFF')`, and added `/manager/vouchers`, `/manager/suppliers`, and `/manager/receipts` for `hasAnyRole('ADMIN','MANAGER')`.

---

## 3. Compliance with Baseline V2.4 Invariants
- **Schema Integrity:** Zero changes to `schema.sql` (21 physical tables + 1 read-only view `inventory_movements`).
- **Error Code Contract:** Zero changes to `constant/ErrorCode.java`.
- **Open Business Decisions (V2.4 §8 Q01–Q05):** Unmodified.
