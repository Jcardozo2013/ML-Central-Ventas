ML CENTRAL VENTAS — Android v1.0

Qué hace
- Recibe una notificación propia cada vez que ML Central v20.16 detecta una venta pagada.
- No depende de WhatsApp.
- Sonido y vibración exclusivos de ventas.
- Historial dentro de la app.
- Contador de ventas no vistas.
- Arranca el receptor al encender el celular.
- Deduplica por order_id: una orden se muestra una sola vez.

Privacidad
- ML Central NO manda nombre, dirección ni teléfono del comprador.
- Sólo producto, cantidad, importe, orden, origen y ganancia si está disponible.
- Esta primera versión usa un canal largo y privado de ntfy.sh como transporte.

Compilar en Android Studio
1. Abrir esta carpeta como proyecto.
2. Esperar que Android Studio descargue el SDK/Gradle que falte.
3. Build > Build APK(s).
4. Instalar app-debug.apk en el celular.
5. Abrir una vez y aceptar permiso de notificaciones.
6. Tocar “Probar aviso y sonido”.

Uso
- Mantener instalada ML Central v20.16 en la PC.
- La PC debe tener Internet.
- El celular debe tener Internet.
- Conviene excluir “ML Central Ventas” del ahorro agresivo de batería del fabricante si el teléfono lo aplica.
