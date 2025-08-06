package fr.ses10doigts.tradeIO5.security.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.ses10doigts.tradeIO5.security.model.payload.request.SignupRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordMatchesValidator
implements ConstraintValidator<PasswordMatches, Object> {

    private static final Logger logger = LoggerFactory.getLogger(PasswordMatchesValidator.class);

    @Override
    public void initialize(PasswordMatches constraintAnnotation) {
    }

    @Override
    public boolean isValid(Object obj, ConstraintValidatorContext context){
	SignupRequest user = (SignupRequest) obj;
	Boolean ok = user.getPassword().equals(user.getVerifPassword());
	if( ok ) {
	    logger.info("Passwords match");
	} else {
	    logger.info("Passwords not matching : " + user.getPassword() + " <> " + user.getVerifPassword());
	}

	return ok;
    }
}