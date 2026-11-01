package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.service.AdminProductService;
import com.ddagtech.aureliabooks.service.MasterDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminProductController.class, properties = "app.r2.enabled=true")
@Import(SecurityConfig.class)
class AdminProductFailureRecoveryTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminProductService products;
    @MockitoBean MasterDataService masterData;
    @BeforeEach void outage() {
        var failure=new DataAccessResourceFailureException("QA private diagnostic");
        doThrow(failure).when(products).createBook(any());
        doThrow(failure).when(products).updateBook(any(),any());
        doThrow(failure).when(products).createStationery(any());
        doThrow(failure).when(products).updateStationery(any(),any());
        when(masterData.getAllActiveCategories()).thenThrow(new CannotCreateTransactionException("QA dropdown unavailable"));
    }
    @ParameterizedTest
    @CsvSource({"books,false,book-form", "books,true,book-form", "stationeries,false,stationery-form", "stationeries,true,stationery-form"})
    void databaseFailurePreservesFormEvenWhenDropdownQueriesAlsoFail(String kind,boolean edit,String template) throws Exception {
        String image="/products/images/550e8400-e29b-41d4-a716-446655440000";
        String path="/manager/"+kind+(edit?"/1/edit":"");
        mvc.perform(post("/qa"+path).contextPath("/qa").with(user("manager").roles("MANAGER")).with(csrf())
                .param("id",edit?"1":"").param("barcode","893700000001").param("title","QA Product")
                .param("categoryId","1").param("price","100000").param("originalCost","60000")
                .param("isbn","0306406152").param("publisherId","1").param("authorIds","1").param("brandId","1")
                .param("mainImageUrl",image))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("admin/products/"+template))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\""+image+"\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("action=\"/qa"+path+"\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("QA private diagnostic"))));
    }
}
