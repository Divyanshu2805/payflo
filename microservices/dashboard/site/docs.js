// Renders docs/api/openapi.yaml with Swagger UI. A separate file because the page's policy allows no inline script.
if (window.SwaggerUIBundle) {
  window.SwaggerUIBundle({
    url: '/openapi.yaml',
    dom_id: '#swagger',
    deepLinking: true,
    docExpansion: 'none',
    defaultModelsExpandDepth: 0,
    persistAuthorization: false,          // a token typed into Authorize is gone when the tab closes
    tryItOutEnabled: true,
  });
}
