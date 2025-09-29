import type {ReactNode} from 'react';
import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

type FeatureItem = {
  title: string;
  Svg: React.ComponentType<React.ComponentProps<'svg'>>;
  description: ReactNode;
};

const FeatureList: FeatureItem[] = [
  {
    title: 'JPA 기반 자동 생성',
    Svg: require('@site/static/img/undraw_docusaurus_mountain.svg').default,
    description: (
      <>
        JPA 애노테이션을 스캔해 스키마 스냅샷을 만들고,
        이전 스냅샷과 비교하여 DB 마이그레이션 SQL을 자동 생성합니다.
      </>
    ),
  },
  {
    title: 'DDL + Liquibase 동시 출력',
    Svg: require('@site/static/img/undraw_docusaurus_tree.svg').default,
    description: (
      <>
        SQL DDL과 Liquibase YAML을 동시에 생성하여
        기존 마이그레이션 도구와의 호환성을 보장합니다.
      </>
    ),
  },
  {
    title: 'MySQL 우선 지원',
    Svg: require('@site/static/img/undraw_docusaurus_react.svg').default,
    description: (
      <>
        현재 MySQL을 우선 지원하며, JDK 21+ 환경에서
        최적화된 성능을 제공합니다.
      </>
    ),
  },
];

function Feature({title, Svg, description}: FeatureItem) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures(): ReactNode {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
