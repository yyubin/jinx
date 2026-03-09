import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://yyubin.github.io',
  base: '/jinx',
  integrations: [
    starlight({
      title: 'Jinx',
      description: 'JPA → DDL Migration Generator',
      social: {
        github: 'https://github.com/yyubin/jinx',
      },
      sidebar: [
        { label: 'Introduction', slug: 'intro' },
        { label: 'Getting Started', slug: 'getting-started' },
        { label: 'Configuration', slug: 'configuration' },
        {
          label: 'How It Works',
          items: [
            { label: 'JPA Processing', slug: 'theory/jpa-processing' },
            { label: 'Relationship & FK Handling', slug: 'theory/relationship-fk' },
            { label: 'Naming Strategy', slug: 'theory/naming-strategy' },
          ],
        },
        {
          label: 'Contributing',
          items: [
            { label: 'Add a New Dialect', slug: 'contribute/add-dialect' },
          ],
        },
      ],
    }),
  ],
});
