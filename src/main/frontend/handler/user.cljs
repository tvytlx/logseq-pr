(ns frontend.handler.user
  (:require [frontend.config :as config]
            [frontend.handler.config :as config-handler]
            [frontend.state :as state]
            [frontend.debug :as debug]
            [clojure.string :as string]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-http.client :as http]
            [cljs.core.async :as async :refer [go go-loop <! timeout]]))

(defn set-preferred-format!
  [format]
  (when format
    (config-handler/set-config! :preferred-format format)
    (state/set-preferred-format! format)))

(defn set-preferred-workflow!
  [workflow]
  (when workflow
    (config-handler/set-config! :preferred-workflow workflow)
    (state/set-preferred-workflow! workflow)))

;;; userinfo, token, login/logout, ...

(defn- parse-jwt [jwt]
  (some-> jwt
          (string/split ".")
          (second)
          (js/atob)
          (js/JSON.parse)
          (js->clj :keywordize-keys true)))

(defn- expired? [parsed-jwt]
  (some->
   (* 1000 (:exp parsed-jwt))
   (tc/from-long)
   (t/before? (t/now))))

(defn- almost-expired?
  "return true when jwt will expire after 1h"
  [parsed-jwt]
  (some->
   (* 1000 (:exp parsed-jwt))
   (tc/from-long)
   (t/before? (-> 1 t/hours t/from-now))))

(defn email []
  (some->
   (state/get-auth-id-token)
   (parse-jwt)
   (:email)))

(defn user-uuid []
  (some->
   (state/get-auth-id-token)
   (parse-jwt)
   (:sub)))

(defn logged-in? []
  (boolean
   (some->
    (state/get-auth-id-token)
    (parse-jwt)
    (expired?)
    (not))))

(defn- clear-tokens []
  (state/set-auth-id-token nil)
  (state/set-auth-access-token nil)
  (state/set-auth-refresh-token nil))

(defn- set-tokens!
  ([id-token access-token]
   (state/set-auth-id-token id-token)
   (state/set-auth-access-token access-token))
  ([id-token access-token refresh-token]
   (state/set-auth-id-token id-token)
   (state/set-auth-access-token access-token)
   (state/set-auth-refresh-token refresh-token)))

(defn login-callback [code]
  (go
    (let [resp (<! (http/get (str "https://" config/API-DOMAIN "/auth_callback?code=" code)
                             {:with-credentials? false}))]
      (if (= 200 (:status resp))
        (-> resp
              (:body)
              (js/JSON.parse)
              (js->clj :keywordize-keys true)
              (as-> $ (set-tokens! (:id_token $) (:access_token $) (:refresh_token $))))
        (debug/pprint "login-callback" resp)))))

(defn logout []
  (clear-tokens))

(defn refresh-id-token&access-token
  "refresh id-token and access-token, if refresh_token expired, clear all tokens
   return true if success, else false"
  []
  (when-let [refresh-token (state/get-auth-refresh-token)]
    (go
      (let [resp (<! (http/get (str "https://" config/API-DOMAIN "/auth_refresh_token?refresh_token=" refresh-token)
                               {:with-credentials? false}))]
        (if (= 400 (:status resp))
          ;; invalid refresh_token
          (do
            (clear-tokens)
            false)
          (do
            (->
             resp
             (as-> $ (and (http/unexceptional-status? (:status $)) $))
             (:body)
             (js/JSON.parse)
             (js->clj :keywordize-keys true)
             (as-> $ (set-tokens! (:id_token $) (:access_token $))))
            true))))))

;;; refresh tokens loop
(def stop-refresh false)
(defn refresh-tokens-loop []
  (debug/pprint "start refresh-tokens-loop")
  (go-loop []
    (<! (timeout 60000))
    (when (state/get-auth-refresh-token)
      (let [id-token (state/get-auth-id-token)]
        (when (or (nil? id-token)
                  (-> id-token (parse-jwt) (almost-expired?)))
          (debug/pprint (str "refresh tokens... " (tc/to-string(t/now))))
          (refresh-id-token&access-token))))
    (when-not stop-refresh
      (recur))))
