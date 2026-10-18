package com.ddagtech.aureliabooks.security;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import java.util.HashMap;
/** Profile actions open Google's account chooser; normal sign-in keeps default behavior. */
public class GoogleAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
 private final DefaultOAuth2AuthorizationRequestResolver delegate;
 public GoogleAuthorizationRequestResolver(ClientRegistrationRepository clients){delegate=new DefaultOAuth2AuthorizationRequestResolver(clients,"/oauth2/authorization");}
 @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request){return customize(request,delegate.resolve(request));}
 @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request,String id){return customize(request,delegate.resolve(request,id));}
 private OAuth2AuthorizationRequest customize(HttpServletRequest request,OAuth2AuthorizationRequest result){
  var session=request.getSession(false);if(result==null||session==null||session.getAttribute("GOOGLE_ACCOUNT_ACTION")==null)return result;
  var params=new HashMap<>(result.getAdditionalParameters());params.put("prompt","select_account");return OAuth2AuthorizationRequest.from(result).additionalParameters(params).build();
 }
}
