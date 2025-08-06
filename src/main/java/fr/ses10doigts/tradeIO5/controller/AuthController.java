package fr.ses10doigts.tradeIO5.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import fr.ses10doigts.tradeIO5.security.jwt.JwtUtils;
import fr.ses10doigts.tradeIO5.security.model.Role;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.model.payload.request.LoginRequest;
import fr.ses10doigts.tradeIO5.security.model.payload.request.SignupRequest;
import fr.ses10doigts.tradeIO5.security.repository.RoleRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.impl.UserDetailsImpl;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    UserRepository		userRepository;

    @Autowired
    RoleRepository		roleRepository;

    @Autowired
    PasswordEncoder		encoder;

    @Autowired
    JwtUtils jwtUtils;

    private static final Logger	logger = LoggerFactory.getLogger(AuthController.class);

//    @GetMapping("/registerRoles")
//    public String allAccess() {
//
//	Long nb = roleRepository.count();
//	if (nb < 3) {
//	    Role u = new Role(ERole.ROLE_USER);
//	    Role m = new Role(ERole.ROLE_MODERATOR);
//	    Role a = new Role(ERole.ROLE_ADMIN);
//
//	    roleRepository.save(u);
//	    roleRepository.save(m);
//	    roleRepository.save(a);
//
//	    return "Done.";
//	}else {
//	    return "Allready Done.";
//	}
//    }

    // sert à quelque chose?
    //    @PostMapping("/signin")
    //    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
    //
    //	Authentication authentication = authenticationManager
    //		.authenticate(new UsernamePasswordAuthenticationToken(loginRequest.getUserName(), loginRequest.getPassword()));
    //
    //	SecurityContextHolder.getContext().setAuthentication(authentication);
    //
    //	UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
    //
    //	ResponseCookie jwtCookie = jwtUtils.generateJwtResponseCookie(userDetails);
    //
    //	List<String> roles = userDetails.getAuthorities().stream()
    //		.map(item -> item.getAuthority())
    //		.collect(Collectors.toList());
    //
    //	return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, jwtCookie.toString())
    //		.body(new UserInfoResponse(userDetails.getId(),
    //			userDetails.getUsername(),
    //			userDetails.getEmail(),
    //			roles));
    //
    //	// il serait bien de tester avec un modelAndView :
    //
    //	// ajouter dans les parametres : HttpServletResponse response
    //	// response.addCookie(new Cookie("COOKIENAME", "The cookie's value"));
    //	// Mais quelle valeur mettre dans "COOKIENAME"?
    //	// puis:
    //	//	ModelAndView mav = new ModelAndView();
    //	//	mav.addObject("loggued", true);
    //	//	mav.addObject("username", loginRequest.getUserName());
    //	//	mav.setViewName("/home");
    //	//	return mav;
    //
    //    }

    @PostMapping("/signinForm")
    public ModelAndView authenticateUserForm(
	    @Valid @ModelAttribute LoginRequest loginRequest, HttpServletResponse response
	    ) {

	try {
	    Authentication authentication = authenticationManager.authenticate(
		    new UsernamePasswordAuthenticationToken(loginRequest.getUserName(), loginRequest.getPassword()));

	    SecurityContextHolder.getContext().setAuthentication(authentication);

	    UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

	    Cookie jwtCookie = jwtUtils.generateJwtCookie(userDetails);

	    response.addCookie(jwtCookie);

	    logger.info("User '" + loginRequest.getUserName() + "' signs in");

	} catch (RuntimeException e) {
	    logger.error("Bad credentials : " + e.getMessage());

	    ModelAndView mav = new ModelAndView("redirect:/login");
	    mav.addObject("error", true);
	    return mav;
	}

	ModelAndView mav = new ModelAndView("redirect:/");
	return mav;
    }

    //    @PostMapping("/signup")
    //    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signUpRequest) {
    //	logger.info("Entering SIGNUP");
    //	if (userRepository.existsByUsername(signUpRequest.getUsername())) {
    //	    logger.info("Bad request : Username is already taken!");
    //	    return ResponseEntity.badRequest().body(new MessageResponse("Error: Username is already taken!"));
    //	}
    //
    //	if (userRepository.existsByEmail(signUpRequest.getEmail())) {
    //	    logger.info("Bad request : Email is already in use!");
    //	    return ResponseEntity.badRequest().body(new MessageResponse("Error: Email is already in use!"));
    //	}
    //
    //
    //	// Create new user's account
    //	User user = new User(signUpRequest.getUsername(),
    //		signUpRequest.getEmail(),
    //		encoder.encode(signUpRequest.getPassword()));
    //
    //	List<String> strRoles = signUpRequest.getRole();
    //	Set<Role> roles = new HashSet<>();
    //
    //
    //
    //	if (strRoles == null) {
    //	    Role userRole = roleRepository.findByName(ERole.ROLE_USER).orElseThrow();
    //	    roles.add(userRole);
    //	} else {
    //
    //	    for (String role : strRoles) {
    //		ERole eRole = null;
    //		switch (role) {
    //		case "admin":
    //		    eRole = ERole.ROLE_ADMIN;
    //		    //		    Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
    //		    //		    .orElseThrow(
    //		    //			    () -> new RuntimeException("Error: Role " + ERole.ROLE_ADMIN + " is not found in DB."));
    //		    //		    roles.add(adminRole);
    //
    //		    break;
    //		case "mod":
    //		    eRole = ERole.ROLE_MODERATOR;
    //		    //		    Role modRole = roleRepository.findByName(ERole.ROLE_MODERATOR)
    //		    //		    .orElseThrow(
    //		    //			    () -> new RuntimeException(
    //		    //				    "Error: Role " + ERole.ROLE_MODERATOR + " is not found in DB."));
    //		    //
    //		    //		    roles.add(modRole);
    //
    //		    break;
    //		default:
    //		    eRole = ERole.ROLE_USER;
    //		    //		    Role userRole = roleRepository.findByName(ERole.ROLE_USER)
    //		    //			    .orElseThrow(
    //		    //				    () -> new RuntimeException("Error: Role " + ERole.ROLE_USER + " is not found in DB."));
    //		    //		    roles.add(userRole);
    //		}
    //
    //		Role userRole = roleRepository.findByName(eRole).orElseThrow();
    //		//			.orElseThrow(
    //		//			() -> new RuntimeException("Error: Role " + eRole + " is not found in DB."));
    //		roles.add(userRole);
    //	    }
    //	}
    //
    //	user.setRoles(roles);
    //	userRepository.save(user);
    //
    //	logger.info("User : " + user);
    //
    //	return ResponseEntity.ok(new MessageResponse("User registered successfully!"));
    //    }

    @PostMapping("/signupForm")
    public ModelAndView registerUserForm(@Valid @ModelAttribute SignupRequest signUpRequest) {
	logger.info("Entering SIGNUP");

	ModelAndView mav = new ModelAndView("redirect:/register");
	// mav.setViewName();
	if (userRepository.existsByUsername(signUpRequest.getUsername())) {
	    logger.info("Bad request : Username '" + signUpRequest.getUsername() + "' is already taken!");

	    mav.addObject("exception", "Username is already taken!");
	    mav.addObject("signUpRequest", signUpRequest);
	    return mav;
	    //	    return ResponseEntity.badRequest().body(new MessageResponse("Error: Username is already taken!"));
	}

	if (userRepository.existsByEmail(signUpRequest.getEmail())) {
	    logger.info("Bad request : Email '" + signUpRequest.getEmail() + "' is already in use!");

	    mav.addObject("exception", "Email is already in use!");
	    mav.addObject("signUpRequest", signUpRequest);
	    return mav;
	    //	    return ResponseEntity.badRequest().body(new MessageResponse("Error: Email is already in use!"));
	}

	// Create new user's account
	Set<Role> roles = signUpRequest.getRoles();
	User user = new User(null, signUpRequest.getUsername(), signUpRequest.getEmail(),
			encoder.encode(signUpRequest.getPassword()), roles, true);

	userRepository.save(user);

	logger.info("User Created: " + user);

	mav.addObject("ok", true);
	mav.addObject("signUpRequest", signUpRequest);
	mav.addObject("allRoles", signUpRequest.getRoles());
	return mav;
    }

    // Sert à quelque chose?
    //    @PostMapping("/signout")
    //    public ResponseEntity<?> logoutUser() {
    //	ResponseCookie cookie = jwtUtils.getCleanJwtResponseCookie();
    //	return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
    //		.body(new MessageResponse("You've been signed out!"));
    //    }

    @GetMapping("/signoutForm")
    public ModelAndView logoutUserForm(HttpServletResponse response) {
	response.addCookie(jwtUtils.getCleanJwtCookie());

	ModelAndView mav = new ModelAndView("redirect:/");
	// mav.addObject("loggued", false);
	return mav;

	//	ResponseCookie cookie = jwtUtils.getCleanJwtCookie();
	//	return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
	//		.body(new MessageResponse("You've been signed out!"));
    }




    @ExceptionHandler(BindException.class)
    public ModelAndView handleBindError(HttpServletRequest req, BindException ex) {
	logger.info("Request: " + req.getRequestURL() + " raised BindException: " + ex);

	Map<String, String> errors = new HashMap<>();
	List<ObjectError> allErrors = ex.getBindingResult().getAllErrors();
	int i = 1;
	for (ObjectError objectError : allErrors) {
	    errors.put(objectError.getObjectName() + i++, objectError.getDefaultMessage());

	}

	ModelAndView mav = null;
	String requestURL = req.getRequestURL().toString();
	if (requestURL.contains("signupForm")) {
	    mav = new ModelAndView("redirect:/register");
	    mav.addAllObjects(errors);

	} else if (requestURL.contains("signinForm")) {
	    mav = new ModelAndView("redirect:/login");
	    mav.addObject("error", true);

	} else {
	    throw new RuntimeException("Unknown URL on BindException : " + requestURL);
	}

	return mav;
    }
}