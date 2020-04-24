import React, { useEffect, useState } from 'react'
import { useWindowDimensions, FlatList, Image, RefreshControl, StatusBar, StyleSheet, View } from 'react-native'
import ContentLoader, { Rect } from 'react-content-loader/native'
import { useTheme, Appbar, Snackbar, Surface, Text, TouchableRipple } from 'react-native-paper'
import ky from 'ky'
import { parse } from 'node-html-parser'
import { SharedElement } from 'react-navigation-shared-element'

import { DefaultImageFeed, IconLive, IconVideo, IconPremium } from '../assets/Icons'
import i18n from '../locales/i18n'

const regex = /<!\[CDATA\[(.*)+\]\]>/

/**
 * @author Matthieu BACHELIER
 * @since 2020-03
 * @version 1.0
 */
export default function HomeScreen({ navigation, route }) {
  const [loading, setLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [fetchFailed, setFetchFailed] = useState(false)
  const [items, setItems] = useState([])

  const { colors } = useTheme()
  const window = useWindowDimensions()

  const styles = StyleSheet.create({
    itemContainer: {
      flex: 1,
      flexDirection: 'row',
      height: 108,
      borderBottomWidth: 1,
      borderBottomColor: colors.border,
    },
    imageBG: {
      resizeMode: 'cover',
      width: 120,
      height: 108,
      alignItems: 'flex-end',
      paddingTop: 4,
      paddingRight: 8,
    },
    image: {
      width: 32,
      height: 32,
    },
    iconPremium: {
      position: 'absolute',
      width: 24,
      height: 24,
      bottom: 4,
      right: 4,
      opacity: 0.2,
    },
  })

  useEffect(() => {
    fetchFeed(route?.params?.uri, false)
  }, [route?.params?.uri])

  const refreshFeed = async () => {
    setRefreshing(true)
    await fetchFeed(route?.params?.uri, true)
    setRefreshing(false)
  }

  const fetchFeed = async (uri, isRefreshing) => {
    if (!isRefreshing) {
      setLoading(true)
    }
    const response = await ky.get(`https://www.lemonde.fr/${uri ? uri : 'rss/une.xml'}`)
    if (!response.ok) {
      setFetchFailed(true)
      return
    }
    const text = await response.text()
    const doc = parse(text)
    const nodeItems = doc.querySelectorAll('item')

    let map = new Map()
    for (const index in nodeItems) {
      let item = { id: index, title: '', description: '', isRestricted: false }
      for (let i = 0; i < nodeItems[index].childNodes.length; i++) {
        const node = nodeItems[index].childNodes[i]
        switch (node.tagName) {
          case 'guid':
            item.link = node.text
            break
          case 'title':
            const title = regex.exec(node.text)
            item.title = title && title.length === 2 ? title[1] : ''
            break
          case 'description':
            const description = regex.exec(node.text)
            item.description = description && description.length === 2 ? description[1] : ''
            break
          case 'media:content':
            if (node.getAttribute('url')) {
              item.uri = node.getAttribute('url')
            }
            break
        }
      }
      map.set(item.link, item)
    }
    setItems(Array.from(map.values()))
    if (!isRefreshing) {
      setLoading(false)
    }
    getPremiumIcons(map)
  }

  const getPremiumIcons = (map) => {
    let subPath = ''
    if (route?.params?.subPath) {
      subPath = '/' + route.params.subPath
    }
    console.log('getPremiumIcons', subPath)
    ky.get(`https://www.lemonde.fr${subPath}`)
      .then((res) => res.text())
      .then((page) => {
        const doc = parse(page)
        const articles = doc.querySelectorAll('.article, .teaser')
        for (const article of articles) {
          if (article.querySelector('span.icon__premium')) {
            const link = article.querySelector('a')
            const href = link?.getAttribute('href')
            if (link && map.has(href)) {
              let item = map.get(href)
              item.isRestricted = true
              map.set(href, item)
            }
          }
        }
        //setMapItems(map)
        setItems(Array.from(map.values()))
      })
  }

  const renderItem = ({ item }) => {
    // Check if 2nd capture group is live/video/other
    const regex = /https:\/\/www\.lemonde\.fr\/([\w-]+)\/(\w+)\/.*/g
    const b = regex.exec(item.link)
    let icon = null
    let isLive = false
    if (b && b.length === 3) {
      if (b[2] === 'live') {
        icon = IconLive
        isLive = true
      } else if (b[2] === 'video') {
        icon = IconVideo
      }
    }
    return (
      <TouchableRipple
        borderless
        rippleColor={colors.accent}
        onPress={() =>
          navigation.navigate('BottomTabsNavigator', {
            item,
            url: item.link,
            isLive,
          })
        }>
        <Surface style={styles.itemContainer}>
          <View style={{ display: 'flex', flexDirection: 'row' }}>
            <SharedElement id={`item.${item.id}.photo`}>
              <Image source={item.uri ? { uri: item.uri } : DefaultImageFeed} style={styles.imageBG} />
            </SharedElement>
            {icon && <Image source={icon} style={{ position: 'absolute', width: 32, height: 32, right: 8, top: 8 }} />}
          </View>
          <View style={{ display: 'flex' }}>
            <Text style={{ padding: 8, width: window.width - 120 }}>{item.title}</Text>
            {!isLive && item.isRestricted && <Image source={IconPremium} style={styles.iconPremium} />}
          </View>
        </Surface>
      </TouchableRipple>
    )
  }

  const renderContentLoader = () => {
    let loaders = []
    const d = (window.height + 24) / 7
    for (let i = 0; i < 8; i++) {
      loaders.push(<Rect key={'r1-' + i} x="0" y={`${i * d}`} rx="0" ry="0" width="126" height={Math.floor(d) - 1} />)
      loaders.push(<Rect key={'r2-' + i} x="130" y={`${10 + i * d}`} rx="0" ry="0" width="250" height="15" />)
      loaders.push(<Rect key={'r3-' + i} x="130" y={`${40 + i * d}`} rx="0" ry="0" width="170" height="12" />)
    }
    return (
      <ContentLoader backgroundColor={colors.border} foregroundColor={colors.background} viewBox={`6 0 ${window.width} ${window.height}`}>
        {loaders}
      </ContentLoader>
    )
  }

  return (
    <Surface
      style={{
        flex: 1,
      }}>
      <StatusBar backgroundColor={'rgba(0,0,0,0.5)'} translucent animated />
      <Appbar.Header style={{ marginTop: StatusBar.currentHeight }}>
        <Appbar.Action icon="menu" onPress={navigation.openDrawer} />
        <Appbar.Content title={route?.params?.title ? route?.params?.title : i18n.t('feeds.latestNews')} />
      </Appbar.Header>
      {loading ? (
        renderContentLoader()
      ) : (
        <FlatList
          data={items}
          extraData={items}
          renderItem={renderItem}
          keyExtractor={(item, index) => index.toString()}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refreshFeed} />}
        />
      )}
      <Snackbar visible={fetchFailed} duration={Snackbar.DURATION_LONG}>
        {i18n.t('home.fetchFailed')}
      </Snackbar>
    </Surface>
  )
}
