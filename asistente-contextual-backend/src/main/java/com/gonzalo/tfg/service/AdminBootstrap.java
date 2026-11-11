package com.gonzalo.tfg.service;

import com.gonzalo.tfg.entity.UserEntity;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Crea el usuario "admin" al arrancar si la tabla de usuarios está vacía,
 * para que una instalación nueva no se quede sin acceso.
 *
 * Antes vivía en un @PostConstruct de UserAdminResource: ese recurso solo se
 * instancia con la primera petición a la API de administración, y el
 * {@code @Transactional} invocado desde la propia clase no se aplicaba, así que
 * en una base de datos vacía no había forma de iniciar sesión.
 */
@ApplicationScoped
public class AdminBootstrap {

    static final String USUARIO_ADMIN = "admin";
    static final String EMPRESA_ADMIN = "empresa-alpha";

    @ConfigProperty(name = "app.bootstrap.admin.password")
    String contrasenaInicial;

    @Transactional
    void alArrancar(@Observes StartupEvent evento) {
        if (UserEntity.count() > 0) {
            return;
        }
        UserEntity admin = new UserEntity();
        admin.nombreUsuario = USUARIO_ADMIN;
        admin.hashContrasena = BcryptUtil.bcryptHash(contrasenaInicial);
        admin.idEmpresa = EMPRESA_ADMIN;
        admin.activo = true;
        admin.persist();
        Log.warnf("Bootstrap: creado el usuario '%s' (empresa %s). Cambia su contraseña si es un entorno real.",
                USUARIO_ADMIN, EMPRESA_ADMIN);
    }
}
