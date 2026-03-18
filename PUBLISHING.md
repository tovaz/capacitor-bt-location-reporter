# Publicar el plugin en GitHub y npm

## 1. Crear el repositorio en GitHub

```bash
cd capacitor-bt-location-reporter

# Inicializar git (si aún no está)
git init
git add .
git commit -m "feat: initial implementation of capacitor-bt-location-reporter"

# Crear el repo en GitHub (requiere GitHub CLI instalado)
gh repo create YOUR_ORG/capacitor-bt-location-reporter --public --source=. --push

# O manualmente: crea el repo en github.com y luego:
git remote add origin https://github.com/YOUR_ORG/capacitor-bt-location-reporter.git
git push -u origin main
```

## 2. Actualizar las referencias en package.json y el podspec

En `package.json`, cambia:
```json
"url": "https://github.com/YOUR_ORG/capacitor-bt-location-reporter.git"
```

En `BtLocationReporter.podspec`, cambia:
```ruby
s.homepage = 'https://github.com/YOUR_ORG/capacitor-bt-location-reporter'
s.author   = { 'Tu Nombre' => 'tu@email.com' }
s.source   = { :git => 'https://github.com/YOUR_ORG/capacitor-bt-location-reporter.git', :tag => s.version.to_s }
```

## 3. Compilar el TypeScript antes de publicar

```bash
npm install
npm run build
```

Esto genera la carpeta `dist/` que npm necesita.

## 4. Publicar en npm

```bash
# Login en npm (solo la primera vez)
npm login

# Publicar
npm publish --access public
```

El paquete quedará en `https://www.npmjs.com/package/@tovaz/capacitor-bt-location-reporter`.

## 5. Publicar como GitHub Package (alternativa a npm)

Si prefieres GitHub Packages en lugar de npm público:

```bash
# En package.json, agrega:
# "publishConfig": { "registry": "https://npm.pkg.github.com" }

npm publish
```

Los usuarios instalarán con:
```bash
npm install @YOUR_ORG/capacitor-bt-location-reporter \
  --registry=https://npm.pkg.github.com
```

## 6. Instalar en el proyecto Ionic desde GitHub (sin publicar en npm)

Durante desarrollo puedes instalar directamente desde GitHub:

```bash
npm install github:YOUR_ORG/capacitor-bt-location-reporter
npx cap sync
```

O desde el path local si el plugin está en la misma máquina:

```bash
npm install ../capacitor-bt-location-reporter
npx cap sync
```

## 7. Crear una release con tag de versión

```bash
git tag v0.1.0
git push origin v0.1.0
```

Luego en GitHub: Releases → Draft a new release → elige el tag v0.1.0.

## 8. Actualizar la versión

Cada vez que hagas cambios:

```bash
npm version patch   # 0.1.0 → 0.1.1
# o
npm version minor   # 0.1.0 → 0.2.0

git push && git push --tags
npm publish
```
