package com.pppp.zhimesh.common.validator;

import com.pppp.zhimesh.common.annotation.NotAllFieldsEmptyCheck;
import com.pppp.zhimesh.common.dto.UserUpdateReq;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;

public class NotAllFieldsNullValidator implements
        ConstraintValidator<NotAllFieldsEmptyCheck, UserUpdateReq> {

    @Override
    public void initialize(NotAllFieldsEmptyCheck constraintAnnotation) {
    }

    @Override
    public boolean isValid(UserUpdateReq value, ConstraintValidatorContext context) {
        Field[] fields = UserUpdateReq.class.getDeclaredFields();
        try {
            for (Field field : fields) {
                field.setAccessible(true);
                Object object = field.get(value);
                if (object instanceof String) {
                    return StringUtils.isNotBlank((String) object);
                } else if (null != object) {
                    return true;
                }
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return true;
    }
}
